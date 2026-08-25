package io.github.tuxkoh.bcsfe;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.AdapterView;
import android.webkit.WebChromeClient;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.google.android.material.button.MaterialButton;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import io.github.tuxkoh.bcsfe.core.SaveDocument;

public final class MainActivity extends AppCompatActivity {
    private static final String AD_LOG_TAG="BCSFE-Ad";
    private FrameLayout content;
    private TextView title;
    private byte[] workingCopy;
    private SaveDocument document;
    private String openedName;
    private Screen screen = Screen.HOME;
    private boolean insideCategory;
    private View editorView;
    private SessionStore sessionStore;
    private String accountPassword;
    private String sessionId;
    private String unsupportedWarningKey;
    private View sessionPanel;
    private ListView sessionList;
    private Screen screenBeforeAbout = Screen.HOME;
    private boolean adUploadInProgress;
    private boolean adScriptReady;
    private boolean adWindowCreated;
    private final ExecutorService networkExecutor=Executors.newSingleThreadExecutor();
    private boolean newSaveInProgress;
    private volatile boolean rootAvailable;
    private boolean rootCheckRunning;
    private View rootLoadButton;
    private View rootWriteButton;
    private boolean errorReportShowing;
    private int activeFeatureId=-1;
    private final class AdDiagnostics {
        @JavascriptInterface public void clickListenerAdded(){android.util.Log.d(AD_LOG_TAG,"JS registered click listener");}
        @JavascriptInterface public void touchListenerAdded(){android.util.Log.d(AD_LOG_TAG,"JS registered touch listener");}
        @JavascriptInterface public void domClick(){android.util.Log.d(AD_LOG_TAG,"DOM click captured");}
        @JavascriptInterface public void windowOpen(){android.util.Log.d(AD_LOG_TAG,"JS called window.open");}
    }

    private enum Screen { HOME, EDITOR, ABOUT }
    private static final int[][] FEATURE_RANGES={{0,4},{4,12},{12,16},{16,23},{23,26},{26,28},{28,29},{29,31},{31,35}};

    private final ActivityResultLauncher<String[]> openDocument = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::loadDocument);
    private final ActivityResultLauncher<String> createDocument = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/octet-stream"), this::writeDocument);

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        content = findViewById(R.id.content);
        sessionStore = new SessionStore(getFilesDir());
        title = findViewById(R.id.title);
        sessionPanel=findViewById(R.id.sessionPanel);sessionList=findViewById(R.id.sessionList);
        findViewById(R.id.sessionToggle).setOnClickListener(v->toggleSessions());
        findViewById(R.id.sessionImportButton).setOnClickListener(v->startNewSession());
        configureSessionList();
        findViewById(R.id.aboutButton).setOnClickListener(v -> showAbout());
        if (!handleSharedIntent(getIntent())&&!restoreSession()) showHome();
        if(state==null)checkForUpdates();
    }

    private void checkForUpdates(){
        Executors.newSingleThreadExecutor().execute(()->{try{UpdateChecker.Result release=UpdateChecker.latest(BuildConfig.VERSION_NAME);if(release!=null)runOnUiThread(()->showUpdateAvailable(release));}catch(Exception ignored){}});
    }
    private void showUpdateAvailable(UpdateChecker.Result release){
        if(isFinishing()||isDestroyed())return;
        String notes=release.body==null?"":release.body.trim();
        if(notes.isEmpty())notes=getString(R.string.update_notes_unavailable);
        if(notes.length()>6000)notes=notes.substring(0,6000)+"\n…";
        new AlertDialog.Builder(this).setTitle(R.string.update_available).setMessage(getString(R.string.update_available_message,BuildConfig.VERSION_NAME,release.version,notes)).setNegativeButton(R.string.close,null).setPositiveButton(R.string.view_release,(dialog,which)->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(release.pageUrl)));}catch(Exception error){Toast.makeText(this,R.string.open_release_failed,Toast.LENGTH_LONG).show();}}).show();
    }

    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleSharedIntent(intent);}

    private void showHome() {
        screen = Screen.HOME;
        title.setText(R.string.app_name);
        View view = inflate(R.layout.screen_home);
        view.findViewById(R.id.openButton).setOnClickListener(v -> openDocument.launch(new String[]{"*/*"}));
        view.findViewById(R.id.receiveButton).setOnClickListener(v -> receiveTransfer());
        view.findViewById(R.id.createSaveButton).setOnClickListener(v -> chooseNewSaveRegion());
        rootLoadButton = view.findViewById(R.id.rootLoadButton);
        updateRootButton(rootLoadButton);
        rootLoadButton.setOnClickListener(v -> { if (rootAvailable) chooseLocalSave(); else Toast.makeText(this, R.string.root_not_detected, Toast.LENGTH_SHORT).show(); });
        checkRootAccess();
        var categories = (android.widget.LinearLayout) view.findViewById(R.id.categories);
        for (String category : getResources().getStringArray(R.array.category_names)) {
            TextView row = new TextView(this);
            row.setText(category);
            row.setTextSize(16);
            row.setTextColor(getColor(R.color.ink));
            row.setBackgroundResource(R.drawable.panel);
            row.setPadding(dp(16), dp(15), dp(16), dp(15));
            var params = new android.widget.LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = dp(8);
            categories.addView(row, params);
        }
    }

    private void updateRootButton(View button) {
        if (button == null) return;
        // Keep it clickable when root is missing so the user gets the
        // explicit diagnostic instead of a silent disabled control.
        button.setEnabled(true);
        button.setAlpha(rootAvailable ? 1f : 0.55f);
    }

    private void checkRootAccess() {
        if (rootCheckRunning) return;
        rootCheckRunning = true;
        Executors.newSingleThreadExecutor().execute(() -> {
            boolean available = RootAccess.isAvailable();
            runOnUiThread(() -> {
                rootCheckRunning = false;
                rootAvailable = available;
                updateRootButton(rootLoadButton);
                updateRootButton(rootWriteButton);
            });
        });
    }

    private void chooseNewSaveRegion() {
        if(newSaveInProgress)return;
        String[] regions=getResources().getStringArray(R.array.transfer_regions);
        new AlertDialog.Builder(this).setTitle(R.string.choose_new_save_region).setItems(regions,(dialog,index)->createNewSave(SaveDocument.Region.values()[index],regions[index])).setNegativeButton(R.string.close,null).show();
    }

    private void createNewSave(SaveDocument.Region region,String regionName) {
        if(newSaveInProgress)return;
        newSaveInProgress=true;
        Toast.makeText(this,R.string.creating_new_save,Toast.LENGTH_LONG).show();
        networkExecutor.execute(()->{
            try(InputStream input=getAssets().open("new_saves/"+region.code()+".save")){
                SaveDocument replacement=SaveDocument.open(io.github.tuxkoh.bcsfe.core.IoStreams.readAll(input));
                TransferClient.AccountResult account=TransferClient.createNewAccount(replacement);
                byte[] data=replacement.toBytes();
                runOnUiThread(()->{
                    if(!activityActive())return;
                    try{
                        String name=getString(R.string.new_save_session_name,regionName);
                        SessionStore.Session session=sessionStore.create(data,name,account.password);
                        sessionId=session.id;document=replacement;workingCopy=data;openedName=name;accountPassword=account.password;
                        showEditor();refreshSessionList();
                    }catch(Exception error){logNetworkFailure("new-save-open",error);Toast.makeText(this,R.string.create_new_save_failed,Toast.LENGTH_LONG).show();}
                    finally{newSaveInProgress=false;}
                });
            }catch(Exception error){
                logNetworkFailure("new-save",error);
                runOnUiThread(()->{if(activityActive())Toast.makeText(this,R.string.create_new_save_failed,Toast.LENGTH_LONG).show();newSaveInProgress=false;});
            }
        });
    }

    private void loadDocument(Uri uri) {
        if (uri == null) return;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalStateException("No stream");
            openImportedBytes(io.github.tuxkoh.bcsfe.core.IoStreams.readAll(input), displayName(uri), null, null, R.string.invalid_save_file);
        } catch (Exception error) {
            reportError("document-read", error);
            Toast.makeText(this, R.string.read_failed, Toast.LENGTH_LONG).show();
        }
    }

    private boolean handleSharedIntent(Intent intent){
        if(intent==null||!Intent.ACTION_SEND.equals(intent.getAction()))return false;Uri uri=intent.getParcelableExtra(Intent.EXTRA_STREAM);if(uri==null&&intent.getClipData()!=null&&intent.getClipData().getItemCount()>0)uri=intent.getClipData().getItemAt(0).getUri();if(uri==null){Toast.makeText(this,R.string.shared_import_failed,Toast.LENGTH_LONG).show();return true;}loadSharedDocument(uri);intent.setAction(null);return true;
    }
    private void loadSharedDocument(Uri uri){
        try(InputStream input=getContentResolver().openInputStream(uri)){if(input==null)throw new IllegalStateException();openImportedBytes(io.github.tuxkoh.bcsfe.core.IoStreams.readAll(input),displayName(uri),null,null,R.string.shared_import_failed);}
        catch(Exception error){reportError("shared-import", error);Toast.makeText(this,R.string.shared_import_failed,Toast.LENGTH_LONG).show();}
    }

    private void openImportedBytes(byte[] data, String name, String password, SaveDocument.Region hint, int invalidMessage) throws Exception {
        openImportedBytes(data, name, password, hint, invalidMessage, null);
    }

    private void openImportedBytes(byte[] data, String name, String password, SaveDocument.Region hint, int invalidMessage, java.util.function.Consumer<SaveDocument> mutator) throws Exception {
        try {
            openImportedDocument(SaveDocument.open(data), data, name, password, mutator);
        } catch (IllegalArgumentException invalid) {
            captureError("save-parse", invalid, data);
            new AlertDialog.Builder(this).setTitle(R.string.force_load_title).setMessage(R.string.force_load_message)
                    .setNegativeButton(R.string.close, null).setPositiveButton(R.string.force_load_confirm, (dialog, which) -> {
                        try {
                            openImportedDocument(SaveDocument.openForInspection(data, hint), data, name, password, mutator);
                        } catch (Exception error) {
                            reportError("save-force-import", error, data);
                            Toast.makeText(this, invalidMessage, Toast.LENGTH_LONG).show();
                        }
                    }).show();
        }
    }

    private void openImportedDocument(SaveDocument replacement, byte[] original, String name, String password) throws Exception {
        openImportedDocument(replacement, original, name, password, null);
    }

    private void openImportedDocument(SaveDocument replacement, byte[] original, String name, String password, java.util.function.Consumer<SaveDocument> mutator) throws Exception {
        if (mutator != null) mutator.accept(replacement);
        byte[] data = replacement.toBytes();
        SessionStore.Session session = sessionStore.create(data, name, password);
        sessionId = session.id;
        document = replacement;
        workingCopy = data;
        openedName = name;
        accountPassword = password;
        showEditor();
        refreshSessionList();
    }

    private void chooseLocalSave() {
        if (!rootAvailable) { Toast.makeText(this, R.string.root_not_detected, Toast.LENGTH_SHORT).show(); return; }
        Toast.makeText(this, R.string.root_checking, Toast.LENGTH_SHORT).show();
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                RootAccess.Detection detection = RootAccess.detect();
                List<SaveDocument.Region> saves = detection.saveRegions();
                List<SaveDocument.Region> installed = detection.installedRegions();
                runOnUiThread(() -> {
                    if (!saves.isEmpty()) {
                        chooseLocalRegion(saves);
                    } else if (!installed.isEmpty()) {
                        chooseCreateForInstalledRegion(installed);
                    } else {
                        Toast.makeText(this, R.string.root_no_any_game, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception error) {
                reportError("root-detect", error);
                runOnUiThread(() -> Toast.makeText(this, R.string.root_not_detected, Toast.LENGTH_LONG).show());
            }
        });
    }

    private void chooseLocalRegion(List<SaveDocument.Region> regions) {
        String[] labels = new String[regions.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = regionDisplay(regions.get(i));
        new AlertDialog.Builder(this).setTitle(R.string.root_choose_game).setItems(labels, (dialog, which) -> loadLocalSave(regions.get(which)))
                .setNegativeButton(R.string.close, null).show();
    }

    private void chooseCreateForInstalledRegion(List<SaveDocument.Region> regions) {
        String[] labels = new String[regions.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = regionDisplay(regions.get(i));
        new AlertDialog.Builder(this).setTitle(R.string.root_no_save).setItems(labels, (dialog, which) -> createNewSave(regions.get(which), labels[which]))
                .setNegativeButton(R.string.close, null).show();
    }

    private void loadLocalSave(SaveDocument.Region region) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                byte[] data = RootAccess.readSave(region);
                runOnUiThread(() -> {
                    try { openImportedBytes(data, "SAVE_DATA (" + regionDisplay(region) + ")", null, region, R.string.root_load_failed); Toast.makeText(this, R.string.root_load_success, Toast.LENGTH_SHORT).show(); }
                    catch (Exception error) { reportError("root-save-parse", error, data); Toast.makeText(this, R.string.root_load_failed, Toast.LENGTH_LONG).show(); }
                });
            } catch (Exception error) {
                reportError("root-save-read", error);
                runOnUiThread(() -> Toast.makeText(this, R.string.root_load_failed, Toast.LENGTH_LONG).show());
            }
        });
    }

    private String regionDisplay(SaveDocument.Region region) {
        String[] labels = getResources().getStringArray(R.array.transfer_regions);
        return labels[region.ordinal()];
    }

    private void showEditor() throws Exception {
        screen = Screen.EDITOR;
        title.setText(openedName);
        View view = inflate(R.layout.screen_editor);
        editorView = view;
        TextView fileInfo = view.findViewById(R.id.fileInfo);
        String hash = hex(MessageDigest.getInstance("SHA-256").digest(workingCopy)).substring(0, 16);
        fileInfo.setText(getString(R.string.file_summary, openedName,
                getString(R.string.bytes_format, workingCopy.length), hash));

        showEditorCategories(view);
        showUnsupportedImportWarningIfNeeded();
    }

    private void showUnsupportedImportWarningIfNeeded() {
        if (document == null || !document.needsUnsupportedImportWarning()) return;
        String key=String.valueOf(sessionId)+":"+document.region().code()+":"+document.gameVersion();
        if (key.equals(unsupportedWarningKey)) return;
        unsupportedWarningKey=key;
        new AlertDialog.Builder(this)
                .setTitle(R.string.unsupported_import_warning_title)
                .setMessage(R.string.unsupported_import_warning)
                .setPositiveButton(R.string.close,null)
                .show();
    }

    private void showEditorCategories(View view) throws Exception {
        insideCategory=false;activeFeatureId=-1;
        List<String> all = Arrays.asList(getResources().getStringArray(R.array.category_names));
        ListView list = view.findViewById(R.id.featureList);
        EditText search = view.findViewById(R.id.search);
        search.setVisibility(View.GONE);
        TextView info=view.findViewById(R.id.fileInfo); if(!info.getText().toString().contains(getString(R.string.choose_category))) info.append("\n"+getString(R.string.choose_category));
        list.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,all));
        list.setOnItemClickListener((parent,row,position,id)->showFeatureCategory(view,position));
        configureEditorActions(view);
    }

    private void showFeatureCategory(View view,int category) {
        insideCategory=true;
        String[] source=getResources().getStringArray(R.array.feature_names); int from=FEATURE_RANGES[category][0],to=FEATURE_RANGES[category][1];
        List<String> all=new ArrayList<>(Arrays.asList(source).subList(from,to));
        ListView list=view.findViewById(R.id.featureList); EditText search=view.findViewById(R.id.search); search.setVisibility(View.VISIBLE);search.setText("");
        Runnable filter = () -> {
            String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            List<String> shown = new ArrayList<>();
            for (String item : all) if (item.toLowerCase(Locale.ROOT).contains(query)) shown.add(item);
            list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, shown));
        };
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { filter.run(); }
            public void afterTextChanged(Editable s) {}
        });
        filter.run();
        list.setOnItemClickListener((parent, row, position, id) -> {
            String feature = ((TextView) row).getText().toString();
            dispatchFeature(Arrays.asList(source).indexOf(feature), feature);
        });
    }

    private void dispatchFeature(int id,String titleText) {
        activeFeatureId=id;
        try { switch(id) {
            case 0 -> editSaveManagement(); case 1 -> editRegion(); case 2 -> editVersion(); case 3 -> confirmUpload();
            case 4 -> editNumber(R.string.catfood_label,document.catFood(),document::setCatFood); case 5 -> editNumber(R.string.xp_label,document.xp(),document::setXp);
            case 6 -> editTickets(); case 7 -> editAdvancedItems(); case 8 -> editBattleItemsMenu(); case 9 -> chooseConsumableGroup(); case 10 -> editOrbsAndMaterials(); case 11 -> editEventItems();
            case 12 -> editCats(); case 13 -> editCatForms(titleText); case 14 -> editCatExtras(); case 15 -> editStorageAndSkills();
            case 16 -> editStory(); case 17 -> editTreasuresAndAku(); case 18 -> editChallengeAndDojo(); case 19 -> editEnigmaAndGauntlets();
            case 20 -> editEventMaps();
            case 21 -> editMapChoice(new SaveDocument.StageMap[]{SaveDocument.StageMap.UNCANNY,SaveDocument.StageMap.CATAMIN,SaveDocument.StageMap.BEHEMOTH},R.array.uncanny_map_names);
            case 22 -> editMapChoice(new SaveDocument.StageMap[]{SaveDocument.StageMap.LEGEND_QUEST,SaveDocument.StageMap.TOWER,SaveDocument.StageMap.ZERO_LEGENDS},R.array.legend_map_names);
            case 23 -> editGamatoto(); case 24 -> editOtoto(); case 25 -> editCatShrine(); case 26 -> editAccountInfo(); case 27 -> showAccountOperations(); case 28 -> editSeeds();
            case 29 -> editCrashFixes(); case 30 -> editMenuFixes(); case 31 -> editLineupsAndGambling(); case 32 -> editOtherGuide(); case 33 -> editRewardsMissionsMedals(); case 34 -> editPassAndRestart();
            default -> Toast.makeText(this,R.string.invalid_feature,Toast.LENGTH_SHORT).show();
        }} catch (RuntimeException error) { showFieldError(); }
    }
    private void editRewardsMissionsMedals() { String[] choices=getResources().getStringArray(R.array.reward_mission_actions);new AlertDialog.Builder(this).setTitle(R.string.rewards_missions_title).setItems(choices,(d,i)->{if(i==0)editRewards();else if(i==1)editLabyrinthMedals();else if(i==2)editMissions();else showGameMedals();}).show(); }
    private void editLabyrinthMedals() { editArray(R.string.labyrinth_medals_title,R.string.labyrinth_medal_label,document.labyrinthMedals(),document::setLabyrinthMedal); }
    private void showGameMedals() { String[] actions=getResources().getStringArray(R.array.game_medal_actions);new AlertDialog.Builder(this).setTitle(R.string.game_medals_title).setItems(actions,(d,i)->{if(i==0)editNumberText(getString(R.string.game_medal_id_label),0,document::addMedal);else chooseMedalToRemove();}).setNegativeButton(R.string.close,null).show(); }
    private void chooseMedalToRemove() { int count=document.medalCount();if(count==0){Toast.makeText(this,R.string.no_game_medals,Toast.LENGTH_SHORT).show();return;}String[] rows=new String[count];for(int i=0;i<count;i++)rows[i]=getString(R.string.game_medal_row,document.medalId(i));new AlertDialog.Builder(this).setTitle(R.string.game_medals_title).setItems(rows,(d,i)->{document.removeMedal(i);persistApplied();}).setNegativeButton(R.string.close,null).show(); }

    private void configureEditorActions(View view) {
        view.findViewById(R.id.exportButton).setOnClickListener(v ->
                createDocument.launch("EDITED_" + (openedName == null ? "SAVE_DATA" : openedName)));
        view.findViewById(R.id.uploadButton).setOnClickListener(v -> confirmUpload());
        rootWriteButton = view.findViewById(R.id.rootWriteButton);
        updateRootButton(rootWriteButton);
        rootWriteButton.setOnClickListener(v -> { if (rootAvailable) confirmRootWrite(); else Toast.makeText(this, R.string.root_not_detected, Toast.LENGTH_SHORT).show(); });
        checkRootAccess();
        view.findViewById(R.id.exitButton).setOnClickListener(v -> confirmExit());
        view.findViewById(R.id.historyButton).setOnClickListener(v -> showHistory());
    }

    private void receiveTransfer() {
        final String[] regionCodes = {"en", "jp", "tw", "kr"};
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(24), dp(8), dp(24), 0);
        EditText transfer = new EditText(this); transfer.setHint(R.string.enter_transfer); transfer.setSingleLine(true);
        transfer.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"));
        EditText pin = new EditText(this); pin.setHint(R.string.enter_pin); pin.setSingleLine(true);
        pin.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789"));
        TextView regionLabel = new TextView(this); regionLabel.setText(R.string.transfer_region); regionLabel.setPadding(0,dp(8),0,0);
        Spinner region = new Spinner(this); String[] regionOptions=getResources().getStringArray(R.array.transfer_regions); region.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, regionOptions));
        String language=getResources().getConfiguration().getLocales().get(0).getLanguage();
        region.setSelection(language.equals("zh")?2:language.equals("ja")?1:language.equals("ko")?3:0);
        form.addView(transfer); form.addView(pin); form.addView(regionLabel); form.addView(region);
        new AlertDialog.Builder(this).setTitle(R.string.receive_transfer).setView(form).setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.receive_transfer, (dialog, which) -> {
                    Toast.makeText(this, R.string.receiving, Toast.LENGTH_SHORT).show();
                    networkExecutor.execute(() -> {
                        try {
                            int selectedRegion = region.getSelectedItemPosition();
                            TransferClient.ReceivedSave received = TransferClient.receive(transfer.getText().toString().trim(), pin.getText().toString().trim(), regionCodes[selectedRegion]);
                            SaveDocument.Region hint = SaveDocument.Region.values()[selectedRegion];
                            runOnUiThread(() -> { if(!activityActive())return; try {
                                openImportedBytes(received.data, "SAVE_DATA", received.password, hint, R.string.receive_failed,
                                        replacement -> {
                                            // Unsupported/forced-import saves are intentionally read-only.
                                            // Their layout may have shifted, so applying the server token
                                            // would turn a successful inspection import into an error.
                                            if (replacement.hasItemProfile()
                                                    && received.passwordRefreshToken != null
                                                    && !received.passwordRefreshToken.isEmpty()) {
                                                replacement.setPasswordRefreshToken(received.passwordRefreshToken);
                                            }
                                        });
                            } catch (Exception error) { logNetworkFailure("receive-open",error,received.data);Toast.makeText(this, R.string.receive_failed, Toast.LENGTH_LONG).show(); } });
                        } catch (Exception error) { logNetworkFailure("receive",error);runOnUiThread(() -> {if(activityActive())Toast.makeText(this, R.string.receive_failed, Toast.LENGTH_LONG).show();}); }
                    });
                }).show();
    }
    private boolean activityActive(){return !isFinishing()&&!isDestroyed();}
    @Override protected void onDestroy(){networkExecutor.shutdownNow();super.onDestroy();}
    private void confirmUpload() {
        if (document == null || (!document.hasItemProfile() && !document.canAttemptUnsafeUpload())) { unsupportedVersion(); return; }
        if (document.canAttemptUnsafeUpload()) {
            new AlertDialog.Builder(this).setTitle(R.string.force_upload_title)
                    .setMessage(R.string.force_upload_message).setNegativeButton(R.string.close, null)
                    .setPositiveButton(R.string.force_upload_confirm, (dialog, which) -> beginUploadConfirmation()).show();
            return;
        }
        beginUploadConfirmation();
    }
    private void beginUploadConfirmation() {
        if(BuildConfig.ADS_ENABLED){showAdActionConfirmation(true);return;}
        new AlertDialog.Builder(this).setTitle(R.string.upload_transfer).setMessage(R.string.upload_warning)
                .setNegativeButton(R.string.close,null).setPositiveButton(R.string.upload_confirm,(d,w)->uploadAndShowTransferCodes()).show();
    }
    private void confirmRootWrite() {
        if (document == null) return;
        if (BuildConfig.ADS_ENABLED) { showAdActionConfirmation(false); return; }
        new AlertDialog.Builder(this).setTitle(R.string.root_write_save)
                .setMessage(getString(R.string.root_write_confirm, regionDisplay(document.region())))
                .setNegativeButton(R.string.close, null).setPositiveButton(R.string.root_ad_action, (d,w)->writeCurrentSaveToGame()).show();
    }

    private void showAdActionConfirmation(boolean upload) {
        if(BuildConfig.ADSTERRA_SCRIPT_URL.isEmpty())return;
        adUploadInProgress=false;
        adScriptReady=false;
        adWindowCreated=false;
        FrameLayout adContainer=new FrameLayout(this);adContainer.setMinimumHeight(dp(480));
        WebView adView=createAdWebView(adContainer);adContainer.addView(adView,new FrameLayout.LayoutParams(-1,dp(480)));
        AlertDialog dialog=new AlertDialog.Builder(this).setView(adContainer).setNegativeButton(R.string.close,null).create();dialog.setCanceledOnTouchOutside(false);final boolean[] uploadStarted={false};final float[] uploadBounds={0.45f,0.60f,1f,1f};
        adView.setOnTouchListener((view,event)->{float x=event.getX()/Math.max(1f,view.getWidth()),y=event.getY()/Math.max(1f,view.getHeight());if(inBounds(x,y,uploadBounds)&&event.getAction()==android.view.MotionEvent.ACTION_UP&&!uploadStarted[0]){if(!adScriptReady){android.util.Log.d(AD_LOG_TAG,"action ignored script-not-ready");return true;}android.util.Log.d(AD_LOG_TAG,"action touch released to WebView");adUploadInProgress=true;uploadStarted[0]=true;view.post(()->{android.util.Log.d(AD_LOG_TAG,"starting background action");boolean started=upload;if(upload)started=uploadAndShowTransferCodes(dialog::dismiss,8000);else started=writeCurrentSaveToGame(dialog::dismiss,8000);if(!started){uploadStarted[0]=false;adUploadInProgress=false;android.util.Log.d(AD_LOG_TAG,"action did not start");}});view.postDelayed(()->((WebView)view).evaluateJavascript("if(document.getElementById('ad-trigger'))document.documentElement.style.visibility='hidden'",null),150);}return false;});
        dialog.setOnDismissListener(d->{android.util.Log.d(AD_LOG_TAG,"dialog dismissed childCount="+adContainer.getChildCount());adUploadInProgress=false;adScriptReady=false;adWindowCreated=false;for(int i=0;i<adContainer.getChildCount();i++){View child=adContainer.getChildAt(i);if(child instanceof WebView){((WebView)child).stopLoading();((WebView)child).destroy();}}adContainer.removeAllViews();});
        dialog.show();
        String scriptUrl=android.text.TextUtils.htmlEncode(BuildConfig.ADSTERRA_SCRIPT_URL),titleText=android.text.TextUtils.htmlEncode(getString(upload?R.string.upload_transfer:R.string.root_write_save)),messageText=android.text.TextUtils.htmlEncode(upload?getString(R.string.upload_warning_with_ad):getString(R.string.root_write_confirm,regionDisplay(document.region()))+"\n\n"+getString(R.string.root_ad_warning)),uploadText=android.text.TextUtils.htmlEncode(getString(upload?R.string.upload_confirm:R.string.root_ad_action)),closeText=android.text.TextUtils.htmlEncode(getString(R.string.close));
        String diagnostics="<script>(function(){var a=EventTarget.prototype.addEventListener,o=window.open;EventTarget.prototype.addEventListener=function(t,l,x){if(t==='click')AdDiag.clickListenerAdded();else if(t==='touchstart'||t==='mousedown')AdDiag.touchListenerAdded();return a.call(this,t,l,x)};document.addEventListener('click',function(){AdDiag.domClick()},true);window.open=function(){AdDiag.windowOpen();return o.apply(window,arguments)}})()</script>";
        String html="<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>html,body{width:100%;height:100%;margin:0;background:#fff;color:#202124;font-family:sans-serif}body{box-sizing:border-box;padding:28px;display:flex;flex-direction:column}h1{font-size:22px;margin:0 0 22px}p{font-size:16px;line-height:1.5;white-space:pre-line;margin:0;flex:1}.actions{display:flex;justify-content:flex-end;align-items:center;margin-top:24px}#ad-trigger{box-sizing:border-box;min-height:48px;padding:14px 20px;border-radius:6px;font-size:15px;font-weight:600;background:#1f5eff;color:#fff;cursor:pointer}#ad-trigger.disabled{opacity:.45}</style>"+diagnostics+"</head><body><h1>"+titleText+"</h1><p>"+messageText+"</p><div class=\"actions\"><div id=\"ad-trigger\" class=\"disabled\" role=\"button\" aria-disabled=\"true\">"+uploadText+"</div></div><script src=\""+scriptUrl+"\"></script></body></html>";
        adView.loadDataWithBaseURL("https://appassets.androidplatform.net/",html,"text/html","UTF-8",null);
        adView.postDelayed(()->readUploadBounds(adView,uploadBounds),500);
    }
    private static boolean inBounds(float x,float y,float[] bounds){return x>=bounds[0]&&y>=bounds[1]&&x<=bounds[2]&&y<=bounds[3];}
    private void readUploadBounds(WebView view,float[] uploadBounds){
        String js="(function(){var e=document.getElementById('ad-trigger'),b=e.getBoundingClientRect();return [b.left/innerWidth,b.top/innerHeight,b.right/innerWidth,b.bottom/innerHeight]})()";
        view.evaluateJavascript(js,value->{try{org.json.JSONArray a=new org.json.JSONArray(value);for(int i=0;i<4;i++)uploadBounds[i]=(float)a.getDouble(i);}catch(Exception ignored){}});
    }
    private WebView createAdWebView(FrameLayout container) {
        WebView webView=new WebView(this);webView.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){if(BuildConfig.ADSTERRA_SCRIPT_URL.equals(request.getUrl().toString()))android.util.Log.d(AD_LOG_TAG,"ad script resource requested");return super.shouldInterceptRequest(view,request);}
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return handleAdNavigation(view,request.getUrl().toString());}
            @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return handleAdNavigation(view,url);}
            @Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap favicon){android.util.Log.d(AD_LOG_TAG,"page started scheme="+safeScheme(url));}
            @Override public void onPageFinished(WebView view,String url){android.util.Log.d(AD_LOG_TAG,"page finished scheme="+safeScheme(url));if(!adUploadInProgress){adScriptReady=true;view.evaluateJavascript("var b=document.getElementById('ad-trigger');if(b){b.classList.remove('disabled');b.setAttribute('aria-disabled','false')}",null);android.util.Log.d(AD_LOG_TAG,"script ready");}promoteAdPageIfReady(container,view,url);}
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){android.util.Log.w(AD_LOG_TAG,"resource error main="+request.isForMainFrame()+" code="+error.getErrorCode()+" scheme="+safeScheme(request.getUrl().toString()));}
            @Override public void onReceivedHttpError(WebView view,WebResourceRequest request,WebResourceResponse response){android.util.Log.w(AD_LOG_TAG,"resource HTTP main="+request.isForMainFrame()+" status="+response.getStatusCode()+" scheme="+safeScheme(request.getUrl().toString()));}
        });
        webView.addJavascriptInterface(new AdDiagnostics(),"AdDiag");
        webView.getSettings().setJavaScriptEnabled(true);webView.getSettings().setDomStorageEnabled(true);webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(true);webView.getSettings().setSupportMultipleWindows(true);webView.getSettings().setAllowFileAccess(false);webView.getSettings().setAllowContentAccess(false);
        webView.setWebChromeClient(new WebChromeClient(){@Override public boolean onCreateWindow(WebView view,boolean isDialog,boolean isUserGesture,android.os.Message resultMsg){android.util.Log.d(AD_LOG_TAG,"window requested userGesture="+isUserGesture);if(!isUserGesture||adWindowCreated){android.util.Log.d(AD_LOG_TAG,"window rejected");return false;}adWindowCreated=true;WebView popup=createAdWebView(container);popup.setAlpha(0f);container.addView(popup,new FrameLayout.LayoutParams(-1,dp(480)));WebView.WebViewTransport transport=(WebView.WebViewTransport)resultMsg.obj;transport.setWebView(popup);resultMsg.sendToTarget();return true;}});
        return webView;
    }
    private void promoteAdPageIfReady(FrameLayout container,WebView candidate,String url){
        if(!adUploadInProgress||url==null)return;
        String scheme=Uri.parse(url).getScheme();if(!"http".equalsIgnoreCase(scheme)&&!"https".equalsIgnoreCase(scheme))return;
        candidate.evaluateJavascript("document.getElementById('ad-trigger')!==null",value->{android.util.Log.d(AD_LOG_TAG,"page classified warning="+value);if(!adUploadInProgress||!"false".equals(value))return;for(int i=0;i<container.getChildCount();i++){View child=container.getChildAt(i);if(child instanceof WebView)child.setAlpha(child==candidate?1f:0f);}candidate.bringToFront();android.util.Log.d(AD_LOG_TAG,"ad page promoted");});
    }
    private static String safeScheme(String url){if(url==null)return "none";String scheme=Uri.parse(url).getScheme();return scheme==null?"none":scheme;}
    private boolean handleAdNavigation(WebView view,String url) {
        if(url==null)return true;
        Uri uri=Uri.parse(url);String scheme=uri.getScheme();
        if(scheme==null||scheme.equalsIgnoreCase("http")||scheme.equalsIgnoreCase("https")||scheme.equalsIgnoreCase("about")||scheme.equalsIgnoreCase("data"))return false;
        android.util.Log.d(AD_LOG_TAG,"blocked navigation scheme="+scheme);
        if(scheme.equalsIgnoreCase("intent")){
            try{
                Intent intent=Intent.parseUri(url,Intent.URI_INTENT_SCHEME);
                String fallback=intent.getStringExtra("browser_fallback_url");
                if(fallback!=null&&"https".equalsIgnoreCase(Uri.parse(fallback).getScheme()))view.loadUrl(fallback);
            }catch(Exception ignored){}
        }
        return true;
    }
    private boolean uploadAndShowTransferCodes() {
        return uploadAndShowTransferCodes(null,0);
    }
    private boolean uploadAndShowTransferCodes(Runnable completed,long minimumDisplayMillis) {
        if(!persistSession(true))return false;
        long startedAt=android.os.SystemClock.elapsedRealtime();
        Toast.makeText(this,R.string.uploading,Toast.LENGTH_SHORT).show();
        byte[] source=document.toBytes();
        networkExecutor.execute(()->{try{SaveDocument uploadSource=SaveDocument.openForUpload(source, document.region());TransferClient.UploadResult result=TransferClient.uploadWithReplacementAccount(uploadSource);SaveDocument replacement=uploadSource.canAttemptUnsafeUpload() ? SaveDocument.openForInspection(result.updatedSave, uploadSource.region()) : SaveDocument.open(result.updatedSave);runAfterMinimumDelay(startedAt,minimumDisplayMillis,()->{document=replacement;workingCopy=result.updatedSave;accountPassword=result.password;persistSession(true);if(completed!=null)completed.run();showTransferCodes(result);});}catch(Exception e){logNetworkFailure("upload",e,source);runAfterMinimumDelay(startedAt,minimumDisplayMillis,()->{if(completed!=null)completed.run();Toast.makeText(this,R.string.upload_failed,Toast.LENGTH_LONG).show();});}});
        return true;
    }

    private void writeCurrentSaveToGame() { writeCurrentSaveToGame(null, 0); }

    private boolean writeCurrentSaveToGame(Runnable completed, long minimumDisplayMillis) {
        if (!rootAvailable || document == null) { Toast.makeText(this, R.string.root_not_detected, Toast.LENGTH_SHORT).show(); return false; }
        if (!persistSession(true)) return false;
        long startedAt = android.os.SystemClock.elapsedRealtime();
        SaveDocument.Region target = document.region();
        byte[] source = document.toBytes();
        Toast.makeText(this, R.string.root_writing, Toast.LENGTH_SHORT).show();
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                RootAccess.writeSave(target, source);
                runAfterMinimumDelay(startedAt, minimumDisplayMillis, () -> { if (completed != null) completed.run(); Toast.makeText(this, R.string.root_write_success, Toast.LENGTH_LONG).show(); });
            } catch (Exception error) {
                reportError("root-save-write", error, source);
                runAfterMinimumDelay(startedAt, minimumDisplayMillis, () -> { if (completed != null) completed.run(); Toast.makeText(this, R.string.root_write_failed, Toast.LENGTH_LONG).show(); });
            }
        });
        return true;
    }
    private void runAfterMinimumDelay(long startedAt,long minimumDelayMillis,Runnable action){long elapsed=android.os.SystemClock.elapsedRealtime()-startedAt;content.postDelayed(action,Math.max(0,minimumDelayMillis-elapsed));}
    private void showTransferCodes(TransferClient.UploadResult result) {
        String text=getString(R.string.transfer_result,result.transferCode,result.pin);
        new AlertDialog.Builder(this).setTitle(R.string.upload_success).setMessage(text).setNegativeButton(R.string.close,null)
                .setPositiveButton(R.string.copy_codes,(d,w)->{((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.upload_success),text));}).show();
    }
    private void editSaveManagement() { String[] actions=getResources().getStringArray(R.array.save_management_actions);new AlertDialog.Builder(this).setTitle(R.string.save_management_title).setItems(actions,(d,i)->{if(i==0)createDocument.launch("EDITED_"+(openedName==null?"SAVE_DATA":openedName));else if(i==1)confirmUpload();else confirmExit();}).setNegativeButton(R.string.close,null).show(); }
    private void editRegion() { SaveDocument.Region[] regions=SaveDocument.Region.values();String[] names=getResources().getStringArray(R.array.transfer_regions);String[] labels=new String[regions.length];for(int i=0;i<labels.length;i++)labels[i]=(i<names.length?names[i]:regions[i].code().toUpperCase(Locale.ROOT))+(regions[i]==document.region()?getString(R.string.current_suffix):"");new AlertDialog.Builder(this).setTitle(R.string.convert_region_title).setItems(labels,(d,i)->new AlertDialog.Builder(this).setTitle(R.string.convert_region_title).setMessage(R.string.convert_region_warning).setNegativeButton(R.string.close,null).setPositiveButton(android.R.string.ok,(x,w)->convertRegion(regions[i])).show()).show(); }
    private void convertRegion(SaveDocument.Region target) {
        if (target == document.region()) return;
        Toast.makeText(this,R.string.converting_region,Toast.LENGTH_SHORT).show();
        byte[] source=document.toBytes();SaveDocument.Region sourceRegion=document.region();
        Executors.newSingleThreadExecutor().execute(()->{
            try {
                SaveDocument replacement=SaveDocument.open(source,sourceRegion);
                replacement.convertRegion(target);
                TransferClient.AccountResult account=TransferClient.createNewAccount(replacement);
                runOnUiThread(()->{document=replacement;accountPassword=account.password;workingCopy=replacement.toBytes();persistSession(true);Toast.makeText(this,R.string.convert_region_success,Toast.LENGTH_LONG).show();});
            } catch (UnsupportedOperationException error) {
                runOnUiThread(()->new AlertDialog.Builder(this).setTitle(R.string.convert_region_title).setMessage(R.string.jp_conversion_unavailable).setPositiveButton(R.string.close,null).show());
            } catch (Exception error) {
                reportError("region-convert", error, source);
                runOnUiThread(()->Toast.makeText(this,R.string.convert_region_failed,Toast.LENGTH_LONG).show());
            }
        });
    }
    private void editVersion() { int[] versions=document.region()==SaveDocument.Region.JP?new int[]{150600,150500,150400,150300,150200,150100,150000,140700,140500,140300,140000}:new int[]{150500,150400,150300,150200,150100,150000,140700,140500,140300,140000};String[] labels=new String[versions.length];for(int i=0;i<labels.length;i++)labels[i]=formatVersion(versions[i])+(versions[i]==document.gameVersion()?getString(R.string.current_suffix):"");new AlertDialog.Builder(this).setTitle(R.string.game_version_title).setItems(labels,(d,i)->{if(versions[i]==document.gameVersion())return;new AlertDialog.Builder(this).setTitle(R.string.game_version_title).setMessage(R.string.game_version_warning).setNegativeButton(R.string.close,null).setPositiveButton(android.R.string.ok,(x,w)->convertVersion(versions[i])).show();}).setNegativeButton(R.string.close,null).show(); }
    private void convertVersion(int target) { try{SaveDocument replacement=SaveDocument.open(document.toBytes());replacement.convertGameVersion(target);sessionStore.save(replacement.toBytes(),openedName,accountPassword);document=replacement;workingCopy=replacement.toBytes();showEditor();Toast.makeText(this,R.string.game_version_success,Toast.LENGTH_LONG).show();}catch(UnsupportedOperationException error){new AlertDialog.Builder(this).setTitle(R.string.game_version_title).setMessage(getString(R.string.game_version_message,document.gameVersion())).setPositiveButton(R.string.close,null).show();}catch(Exception error){reportError("version-convert",error);Toast.makeText(this,R.string.game_version_failed,Toast.LENGTH_LONG).show();} }
    private static String formatVersion(int value) { return (value/10000)+"."+((value/100)%100)+"."+(value%100); }
    private void editAccountInfo() { String[] actions=getResources().getStringArray(R.array.account_info_actions);new AlertDialog.Builder(this).setTitle(R.string.account_info_title).setItems(actions,(d,i)->{boolean inquiry=i<2;String value=inquiry?document.inquiryCode():document.passwordRefreshToken();if(i%2==1)editString(actions[i],value,inquiry?document::setInquiryCode:document::setPasswordRefreshToken);else new AlertDialog.Builder(this).setTitle(actions[i]).setMessage(value).setNegativeButton(R.string.close,null).setPositiveButton(R.string.copy_codes,(x,w)->((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText(actions[i],value))).show();}).setNegativeButton(R.string.close,null).show(); }
    private void showAccountOperations() { String[] actions=getResources().getStringArray(R.array.account_operation_actions);new AlertDialog.Builder(this).setTitle(R.string.account_operations_title).setItems(actions,(d,i)->{if(i==0)new AlertDialog.Builder(this).setTitle(R.string.account_operations_title).setMessage(R.string.account_operations_warning).setNegativeButton(R.string.close,null).setPositiveButton(R.string.create_account_confirm,(x,w)->createNewAccount()).show();else new AlertDialog.Builder(this).setTitle(R.string.upload_managed_items).setMessage(R.string.upload_managed_warning).setNegativeButton(R.string.close,null).setPositiveButton(R.string.upload_confirm,(x,w)->uploadManagedItems()).show();}).setNegativeButton(R.string.close,null).show(); }
    private void createNewAccount() { Toast.makeText(this,R.string.creating_account,Toast.LENGTH_SHORT).show();byte[] source=document.toBytes();Executors.newSingleThreadExecutor().execute(()->{try{SaveDocument replacement=SaveDocument.open(source);TransferClient.AccountResult account=TransferClient.createNewAccount(replacement);runOnUiThread(()->{document=replacement;accountPassword=account.password;workingCopy=replacement.toBytes();persistSession(true);Toast.makeText(this,R.string.create_account_success,Toast.LENGTH_LONG).show();});}catch(Exception error){logNetworkFailure("create-account",error);runOnUiThread(()->Toast.makeText(this,R.string.create_account_failed,Toast.LENGTH_LONG).show());}}); }
    private void uploadManagedItems() { if(!persistSession(true))return;Toast.makeText(this,R.string.uploading_managed_items,Toast.LENGTH_SHORT).show();Executors.newSingleThreadExecutor().execute(()->{try{TransferClient.ManagedUploadResult result=TransferClient.uploadManagedItems(document,accountPassword);SaveDocument replacement=SaveDocument.open(result.updatedSave);runOnUiThread(()->{document=replacement;workingCopy=result.updatedSave;accountPassword=result.password;persistSession(true);Toast.makeText(this,R.string.upload_managed_success,Toast.LENGTH_LONG).show();});}catch(Exception error){logNetworkFailure("managed-items",error);runOnUiThread(()->Toast.makeText(this,R.string.upload_managed_failed,Toast.LENGTH_LONG).show());}}); }

    private void logNetworkFailure(String action,Exception error) {
        logNetworkFailure(action,error,null);
    }

    private void logNetworkFailure(String action,Exception error,byte[] save) {
        android.util.Log.e("BCSFE-Network",action+" failed: "+TransferClient.safeError(error));
        reportError("network-"+action,error,save);
    }

    private void writeDocument(Uri uri) {
        if (uri == null || workingCopy == null) return;
        try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) throw new IllegalStateException("No stream");
            output.write(document == null ? workingCopy : document.toBytes());
            Toast.makeText(this, R.string.exported, Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            reportError("document-export", error, workingCopy);
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_LONG).show();
        }
    }

    private interface NumberChange { void apply(int value); }
    private interface IndexedNumberChange { void apply(int index, int value); }
    private interface StringChange { void apply(String value); }
    private void editTickets() {
        if (!document.hasItemProfile()) { unsupportedVersion(); return; }
        String[] labels = getResources().getStringArray(R.array.ticket_labels);
        int[] values = {document.normalTickets(), document.rareTickets(), document.platinumTickets(), document.legendTickets()};
        NumberChange[] changes = {document::setNormalTickets, document::setRareTickets, document::setPlatinumTickets, document::setLegendTickets};
        String[] rows={labels[0]+": "+values[0],labels[1]+": "+values[1],labels[2]+": "+values[2],labels[3]+": "+values[3],labels[4]};new AlertDialog.Builder(this).setTitle(R.string.tickets_title).setItems(rows,(d,i)->{if(i<4)editNumberText(labels[i],values[i],changes[i]);else tradeRareTickets();}).setNegativeButton(R.string.close,null).show();
    }
    private void tradeRareTickets() { EditText field=numberField(R.string.rare_trade_amount,"1");new AlertDialog.Builder(this).setTitle(R.string.rare_trade_title).setView(field).setNegativeButton(R.string.close,null).setPositiveButton(android.R.string.ok,(d,w)->{try{document.tradeRareTickets(Integer.parseInt(field.getText().toString()));persistApplied();}catch(IllegalStateException e){Toast.makeText(this,R.string.storage_full,Toast.LENGTH_LONG).show();}catch(Exception e){Toast.makeText(this,R.string.invalid_number,Toast.LENGTH_SHORT).show();}}).show(); }
    private void editSeeds() {
        if(!document.hasItemProfile()){unsupportedVersion();return;}
        String[] labels=getResources().getStringArray(R.array.seed_labels);long[] values={document.rareSeed(),document.normalSeed(),document.eventSeed()};LongChange[] changes={document::setRareSeed,document::setNormalSeed,document::setEventSeed};String[] rows=new String[labels.length];for(int i=0;i<labels.length;i++)rows[i]=labels[i]+": "+values[i];new AlertDialog.Builder(this).setTitle(R.string.seeds_title).setItems(rows,(d,i)->editLongText(labels[i],values[i],changes[i])).setNegativeButton(R.string.close,null).show();
    }
    private void editAdvancedItems() {
        if (!document.hasItemProfile()) { unsupportedVersion(); return; }
        String[] labels = getResources().getStringArray(R.array.advanced_item_labels);
        int[] values = {document.platinumShards(), document.np(), document.leadership()};
        NumberChange[] changes = {document::setPlatinumShards, document::setNp, document::setLeadership};
        String[] rows={labels[0]+": "+values[0],labels[1]+": "+values[1],labels[2]+": "+values[2]};new AlertDialog.Builder(this).setTitle(R.string.advanced_items_title).setItems(rows,(d,i)->editNumberText(labels[i],values[i],changes[i])).setNegativeButton(R.string.close,null).show();
    }
    private void chooseNumberEditor(int title, String[] labels, int[] values, NumberChange[] changes) {
        String[] rows = new String[labels.length]; for (int i=0;i<labels.length;i++) rows[i] = labels[i] + ": " + values[i];
        new AlertDialog.Builder(this).setTitle(title).setItems(rows, (dialog, index) -> editNumberText(labels[index], values[index], changes[index])).setNegativeButton(R.string.close, null).show();
    }
    private void unsupportedVersion() { new AlertDialog.Builder(this).setMessage(R.string.unsupported_save_version).setPositiveButton(R.string.close, null).show(); }
    private void chooseConsumableGroup() {
        if (!document.hasItemProfile()) { unsupportedVersion(); return; }
        String[] groups=getResources().getStringArray(R.array.consumable_groups);
        new AlertDialog.Builder(this).setTitle(R.string.consumables_title).setItems(groups,(d,i)->{
            try {
                if(i==0)editArray(R.string.catseyes_title,R.string.catseye_label,document.catseyes(),document::setCatseye);
                else if(i==1)editCatfruit();
                else editArray(R.string.catamins_title,R.string.catamin_label,document.catamins(),document::setCatamin);
            } catch (RuntimeException error) {
                showFieldError(error);
            }
        }).setNegativeButton(R.string.close,null).show();
    }
    private void editCatfruit() {
        try {
            String[] actions=getResources().getStringArray(R.array.catfruit_actions);
            new AlertDialog.Builder(this).setTitle(R.string.catfruit_title).setItems(actions,(dialog,index)->{
                try {
                    int[] values=document.catfruit();
                    if(index==0)editArray(R.string.catfruit_title,R.string.catfruit_label,values,document::setCatfruit);
                    else editNumberText(getString(R.string.catfruit_set_all_label),values[0],document::setAllCatfruit);
                } catch (RuntimeException error) {
                    showFieldError(error);
                }
            }).setNegativeButton(R.string.close,null).show();
        } catch (RuntimeException error) {
            showFieldError(error);
        }
    }
    private void editBattleItems() {
        if (!document.hasItemProfile()) { unsupportedVersion(); return; }
        editArray(R.string.battle_items_title, R.string.battle_item_label, document.battleItems(), document::setBattleItem);
    }
    private void editBattleItemsMenu() { String[] actions=getResources().getStringArray(R.array.battle_item_actions);new AlertDialog.Builder(this).setTitle(R.string.battle_items_title).setItems(actions,(d,i)->{if(i==0)editBattleItems();else editEndlessBattleItems();}).setNegativeButton(R.string.close,null).show(); }
    private void editEndlessBattleItems() { String[] actions={getString(R.string.endless_one_action),getString(R.string.endless_all_action)};new AlertDialog.Builder(this).setTitle(R.string.endless_duration_title).setItems(actions,(d,i)->{if(i==0)requestIndex(R.string.battle_item_id_label,6,this::editEndlessBattleItem);else editEndlessDuration(-1);}).setNegativeButton(R.string.close,null).show(); }
    private void editEndlessBattleItem(int index) { editEndlessDuration(index); }
    private void editEndlessDuration(int index) { EditText field=new EditText(this);field.setSingleLine(true);field.setHint(R.string.endless_duration_label);double current=index<0?0:document.endlessBattleDurationMinutes(index);field.setText(Double.isInfinite(current)?getString(R.string.infinity):Double.toString(current));new AlertDialog.Builder(this).setTitle(R.string.endless_duration_title).setView(field).setNegativeButton(R.string.close,null).setPositiveButton(android.R.string.ok,(d,w)->{try{String raw=field.getText().toString().trim();double minutes=raw.equalsIgnoreCase(getString(R.string.infinity))?Double.POSITIVE_INFINITY:Double.parseDouble(raw);if(index<0)for(int item=0;item<6;item++)document.setEndlessBattleDurationMinutes(item,minutes);else document.setEndlessBattleDurationMinutes(index,minutes);persistApplied();}catch(Exception e){Toast.makeText(this,R.string.invalid_number,Toast.LENGTH_SHORT).show();}}).show(); }
    private void editOrbsAndMaterials() { String[] actions=getResources().getStringArray(R.array.orb_material_actions);new AlertDialog.Builder(this).setTitle(R.string.orb_material_title).setItems(actions,(d,i)->runFieldAction(()->{if(i==0)editTalentOrbs();else editBaseMaterials();})).setNegativeButton(R.string.close,null).show(); }
    private void editTalentOrbs() { String[] actions=getResources().getStringArray(R.array.talent_orb_actions);new AlertDialog.Builder(this).setTitle(R.string.talent_orbs_title).setItems(actions,(d,i)->runFieldAction(()->{if(i==0)addTalentOrb();else if(i==3)chooseBatchOrbGrade();else if(document.talentOrbCount()==0)Toast.makeText(this,R.string.no_talent_orbs,Toast.LENGTH_SHORT).show();else if(i==1)chooseTalentOrb(false);else chooseTalentOrb(true);})).setNegativeButton(R.string.close,null).show(); }
    private void chooseBatchOrbGrade() { String[] source=getResources().getStringArray(R.array.talent_orb_grades);String[] grades=java.util.Arrays.copyOf(source,source.length);grades[grades.length-1]=getString(R.string.talent_orb_all_grade);new AlertDialog.Builder(this).setTitle(R.string.talent_orb_batch_grade_title).setItems(grades,(d,choice)->editTalentOrbsAtGrade(choice==grades.length-1?-1:choice)).setNegativeButton(R.string.close,null).show(); }
    private void editTalentOrbsAtGrade(int grade) { editNumberText(getString(grade<0?R.string.talent_orb_all_grade:R.string.talent_orb_grade_label,grade<0?0:grade),0,v->{for(int id=0;id<310;id++)if(grade<0||TalentOrbNames.grade(id)==grade)document.addTalentOrb(id,v);}); }
    private void chooseTalentOrbGroup(int mode) { String[] groups=getResources().getStringArray(mode==0?R.array.talent_orb_grades:mode==1?R.array.talent_orb_attributes:R.array.talent_orb_effects);new AlertDialog.Builder(this).setTitle(R.string.talent_orb_group_title).setItems(groups,(d,g)->{if(mode==0&&g==groups.length-1){editAllTalentOrbs();return;}int[] attributeIds={0,1,2,3,4,5,6,7,11};int expected=mode==1?attributeIds[g]:g;int count=document.talentOrbCount();ArrayList<Integer> selected=new ArrayList<>();for(int i=0;i<count;i++){int id=document.talentOrbId(i),key=mode==0?TalentOrbNames.grade(id):mode==1?TalentOrbNames.attribute(id):TalentOrbNames.effect(id);if(key==expected)selected.add(i);}if(selected.isEmpty()){Toast.makeText(this,R.string.no_talent_orbs,Toast.LENGTH_SHORT).show();return;}editSelectedTalentOrbs(selected);}).setNegativeButton(R.string.close,null).show(); }
    private void editSelectedTalentOrbs(ArrayList<Integer> selected) { editNumberText(getString(R.string.talent_orb_batch_label,selected.size()),document.talentOrbAmount(selected.get(0)),v->{for(int index:selected)document.setTalentOrbAmount(index,v);}); }
    private void editAllTalentOrbs() { editNumberText(getString(R.string.talent_orb_set_all),0,v->{for(int id=0;id<310;id++)document.addTalentOrb(id,v);}); }
    private void chooseTalentOrb(boolean remove) { int count=document.talentOrbCount();String[] rows=new String[count];for(int i=0;i<count;i++){int id=document.talentOrbId(i);String name=TalentOrbNames.name(document.region(),id);rows[i]=name==null?getString(R.string.talent_orb_row,id,document.talentOrbAmount(i)):getString(R.string.talent_orb_named_row,name,id,document.talentOrbAmount(i));}new AlertDialog.Builder(this).setTitle(R.string.talent_orbs_title).setItems(rows,(d,i)->{if(remove){document.removeTalentOrb(i);persistApplied();}else editNumberText(rows[i],document.talentOrbAmount(i),v->document.setTalentOrbAmount(i,v));}).setNegativeButton(R.string.close,null).setNeutralButton(R.string.talent_orb_set_all,(d,w)->editAllTalentOrbs()).show(); }
    private void addTalentOrb() { chooseOrbForAdd(0,-1,-1); }
    private void chooseOrbForAdd(int mode,int grade,int attribute) { if(mode==2){chooseOrbEffectForAdd(grade,attribute);return;}int array=mode==0?R.array.talent_orb_grades:R.array.talent_orb_attributes;String[] groups=getResources().getStringArray(array);if(mode==0)groups=java.util.Arrays.copyOf(groups,groups.length-1);new AlertDialog.Builder(this).setTitle(mode==0?R.string.talent_orb_grade_title:R.string.talent_orb_attribute_title).setItems(groups,(d,choice)->{if(mode==0)chooseOrbForAdd(1,choice,-1);else chooseOrbEffectForAdd(grade,new int[]{0,1,2,3,4,5,6,7,11,-1}[choice]);}).setNegativeButton(R.string.close,null).show(); }
    private void chooseOrbEffectForAdd(int grade,int attribute) { String[] all=getResources().getStringArray(R.array.talent_orb_effects);ArrayList<Integer> effects=new ArrayList<>();ArrayList<String> labels=new ArrayList<>();for(int effect=0;effect<all.length;effect++){for(int id=0;id<310;id++)if(TalentOrbNames.grade(id)==grade&&TalentOrbNames.attribute(id)==attribute&&TalentOrbNames.effect(id)==effect){effects.add(effect);labels.add(all[effect]);break;}}if(effects.isEmpty()){Toast.makeText(this,R.string.no_matching_talent_orb,Toast.LENGTH_SHORT).show();return;}new AlertDialog.Builder(this).setTitle(R.string.talent_orb_effect_title).setItems(labels.toArray(new String[0]),(d,choice)->{int selected=-1;for(int id=0;id<310;id++)if(TalentOrbNames.grade(id)==grade&&TalentOrbNames.attribute(id)==attribute&&TalentOrbNames.effect(id)==effects.get(choice)){selected=id;break;}final int orbId=selected;editNumberText(TalentOrbNames.name(document.region(),orbId),0,v->{document.addTalentOrb(orbId,v);persistApplied();});}).setNegativeButton(R.string.close,null).show(); }
    private void editEventItems() { String[] actions=getResources().getStringArray(R.array.event_item_actions);new AlertDialog.Builder(this).setTitle(R.string.event_items_title).setItems(actions,(d,i)->{if(i==0)requestIndex(R.string.event_ticket_id_label,document.eventTicketCount(),id->editNumberText(getString(R.string.ticket_amount_label),document.eventTicket(id),v->document.setEventTicket(id,v)));else if(i==1)requestIndex(R.string.lucky_ticket_id_label,document.luckyTicketCount(),id->editNumberText(getString(R.string.ticket_amount_label),document.luckyTicket(id),v->document.setLuckyTicket(id,v)));else if(i==2)editTreasureGroup();else editSchemeItems();}).setNegativeButton(R.string.close,null).show(); }
    private void editSchemeItems() { String[] actions=getResources().getStringArray(R.array.scheme_item_actions);new AlertDialog.Builder(this).setTitle(R.string.scheme_items_title).setItems(actions,(d,i)->editNumberText(getString(R.string.scheme_item_id_label),0,id->{if(i==0)document.addSchemeItem(id);else document.removeSchemeItem(id);})).setNegativeButton(R.string.close,null).show(); }
    private void editCatForms(String feature) {
        if (!document.hasCatProfile()) { unsupportedVersion(); return; }
        boolean forms = feature.contains("True") || feature.contains("三阶");
        String[] actions = forms ? getResources().getStringArray(R.array.form_actions) : getResources().getStringArray(R.array.cat_actions);
        new AlertDialog.Builder(this).setTitle(forms ? R.string.forms_title : R.string.cats_title).setItems(actions,(d,index)->{
            try {
                if (!forms) { if(index==0) document.unlockAllCats(); else if(index==1)document.removeAllCats();else {confirmCatReset(-1);return;} }
                else if(index==0)document.unlockTrueForms();else if(index==1)document.forceTrueForms();else if(index==2)document.removeTrueForms();else if(index==3)document.unlockFourthForms();else if(index==4)document.forceFourthForms();else document.removeFourthForms();
                workingCopy=document.toBytes(); persistSession(); Toast.makeText(this,R.string.edit_applied,Toast.LENGTH_SHORT).show();
            } catch (UnsupportedOperationException e) { unsupportedVersion(); }
        }).setNegativeButton(R.string.close,null).show();
    }
    private void editCats() {
        if(!document.hasCatProfile()){unsupportedVersion();return;}
        String[] actions=getResources().getStringArray(R.array.cat_editor_actions);
        new AlertDialog.Builder(this).setTitle(R.string.cats_title).setItems(actions,(d,index)->{
            if(index==0) editCatById();
            else if(index==1)documentAction(document::unlockAllObtainableCats);
            else if(index==2)documentAction(document::unlockAllCats);
            else if(index==3)documentAction(document::removeAllCats);
            else if(index==4)editNumber(R.string.all_cat_base_level,1,document::setAllCatBaseLevels);
            else if(index==5)editNumber(R.string.all_cat_plus_level,0,document::setAllCatPlusLevels);
            else confirmCatReset(-1);
        }).setNegativeButton(R.string.close,null).show();
    }
    private void editCatById() {
        requestIndex(R.string.cat_id_label,document.catCount(),index->{
            int[] values={document.catBaseLevel(index),document.catPlusLevel(index)};
            String[] labels=getResources().getStringArray(R.array.cat_detail_labels);
            String[] rows={labels[0]+": "+(document.catUnlocked(index)?getString(R.string.yes):getString(R.string.no)),labels[1]+": "+values[0],labels[2]+": "+values[1],labels[3]};
            new AlertDialog.Builder(this).setTitle(getString(R.string.cat_number,index)).setItems(rows,(d,item)->{
                if(item==0){document.setCatUnlocked(index,!document.catUnlocked(index));persistApplied();}
                else if(item==1)editNumberText(labels[1],values[0],v->document.setCatBaseLevel(index,v));
                else if(item==2)editNumberText(labels[2],values[1],v->document.setCatPlusLevel(index,v));
                else confirmCatReset(index);
            }).setNegativeButton(R.string.close,null).show();
        });
    }
    private void confirmCatReset(int cat) { int message=cat<0?R.string.cat_reset_all_confirm:R.string.cat_reset_confirm;new AlertDialog.Builder(this).setTitle(R.string.cat_reset_title).setMessage(message).setNegativeButton(R.string.close,null).setPositiveButton(R.string.cat_reset_action,(d,w)->{if(cat<0)document.resetAllCats();else document.resetCat(cat);persistApplied();}).show(); }
    private void editCatExtras() {
        String[] actions=getResources().getStringArray(R.array.cat_extra_actions);
        new AlertDialog.Builder(this).setTitle(R.string.cat_extras_title).setItems(actions,(d,choice)->{
            if(choice>=2){editAllCatExtras(choice);return;}
            requestIndex(R.string.cat_id_label,document.catCount(),index->{
                if(choice==0){document.setCatGuideCollected(index,!document.catGuideCollected(index));persistApplied();}
                else editTalents(index);
            });
        }).setNegativeButton(R.string.close,null).show();
    }
    private void editAllCatExtras(int choice) { if(choice==2){document.setAllCatGuideCollected(true);persistApplied();}else if(choice==3){document.setAllCatGuideCollected(false);persistApplied();}else{document.maxAllCatTalents();persistApplied();} }
    private void documentAction(Runnable action) { action.run();persistApplied(); }
    private void editTalents(int cat) {
        List<SaveDocument.TalentValue> talents=document.catTalents(cat);if(talents.isEmpty()){Toast.makeText(this,R.string.no_talents,Toast.LENGTH_SHORT).show();return;}
        String[] rows=new String[talents.size()];for(int i=0;i<rows.length;i++)rows[i]=getString(R.string.talent_row,talents.get(i).id,talents.get(i).level);
        new AlertDialog.Builder(this).setTitle(R.string.talents_title).setItems(rows,(d,index)->editNumberText(getString(R.string.talent_id,talents.get(index).id),talents.get(index).level,v->document.setCatTalentLevel(cat,index,v))).setNegativeButton(R.string.close,null).show();
    }
    private void editSpecialSkills() {
        String[] names=getResources().getStringArray(R.array.special_skill_names);String[] rows=new String[document.specialSkillCount()];for(int i=0;i<rows.length;i++)rows[i]=getString(R.string.special_skill_row,names[i],i+1,document.specialSkillBaseLevel(i),document.specialSkillPlusLevel(i));
        new AlertDialog.Builder(this).setTitle(R.string.special_skills_title).setItems(rows,(d,index)->runFieldAction(()->{
            String[] labels=getResources().getStringArray(R.array.skill_level_labels);int[] values={document.specialSkillBaseLevel(index),document.specialSkillPlusLevel(index)};NumberChange[] changes={v->document.setSpecialSkillBaseLevel(index,v),v->document.setSpecialSkillPlusLevel(index,v)};chooseNumberEditor(R.string.special_skills_title,labels,values,changes);
        })).setNegativeButton(R.string.close,null).show();
    }
    private void editStorageAndSkills() { String[] actions=getResources().getStringArray(R.array.storage_skill_actions);new AlertDialog.Builder(this).setTitle(R.string.storage_skills_title).setItems(actions,(d,i)->{if(i==0)addStorageItems(true);else if(i==1)addStorageItems(false);else if(i==2)removeStorageItem();else if(i==3){document.clearStorage();persistApplied();}else editSpecialSkills();}).setNegativeButton(R.string.close,null).show(); }
    private void addStorageItems(boolean cats) { LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(24),dp(8),dp(24),0);EditText id=numberField(cats?R.string.cat_id_label:R.string.special_skill_id_label,"0");EditText quantity=numberField(R.string.storage_quantity_label,"1");form.addView(id);form.addView(quantity);new AlertDialog.Builder(this).setTitle(cats?R.string.storage_add_cats:R.string.storage_add_skills).setView(form).setNegativeButton(R.string.close,null).setPositiveButton(android.R.string.ok,(d,w)->{try{int itemId=Integer.parseInt(id.getText().toString()),amount=Integer.parseInt(quantity.getText().toString());if(cats)document.addStorageCats(itemId,amount);else document.addStorageSpecialSkills(itemId,amount);persistApplied();}catch(IllegalStateException e){Toast.makeText(this,R.string.storage_full,Toast.LENGTH_LONG).show();}catch(Exception e){showFieldError();}}).show(); }
    private void removeStorageItem() { int count=document.occupiedStorageCount();if(count==0){Toast.makeText(this,R.string.storage_empty,Toast.LENGTH_SHORT).show();return;}String[] rows=new String[count];for(int i=0;i<count;i++){int slot=document.occupiedStorageSlot(i);rows[i]=getString(R.string.storage_item_row,document.storageItemType(slot),document.storageItemId(slot));}new AlertDialog.Builder(this).setTitle(R.string.storage_remove).setItems(rows,(d,i)->{document.removeOccupiedStorageItem(i);persistApplied();}).setNegativeButton(R.string.close,null).show(); }
    private void editCrashFixes() { String[] actions=getResources().getStringArray(R.array.crash_fix_actions);new AlertDialog.Builder(this).setTitle(R.string.fix_title).setItems(actions,(d,i)->{if(i==0)document.fixGamatotoCrash();else if(i==1)document.fixOtotoValues();else document.fixTimeErrors(System.currentTimeMillis()/1000L);persistApplied();}).setNegativeButton(R.string.close,null).show(); }
    private void editMenuFixes() { String[] actions=getResources().getStringArray(R.array.menu_fix_actions);new AlertDialog.Builder(this).setTitle(R.string.fix_title).setItems(actions,(d,i)->{if(i==0)document.unlockEquipMenu();else document.fixOfficerPass();persistApplied();}).setNegativeButton(R.string.close,null).show(); }
    private void editOtherGuide() {
        String[] actions=getResources().getStringArray(R.array.other_guide_actions);
        new AlertDialog.Builder(this).setTitle(R.string.other_guide_title).setItems(actions,(d,index)->{
            if(index==0)editPlayTime();
            else if(index==1)requestEnemyGuideId(id->{document.setEnemyGuideUnlocked(id,!document.enemyGuideUnlocked(id));persistApplied();});
            else {document.setAllEnemyGuide(index==2);persistApplied();}
        }).setNegativeButton(R.string.close,null).show();
    }
    private void editPlayTime() { LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(24),dp(8),dp(24),0);EditText hours=numberField(R.string.playtime_hours_label,Integer.toString(document.playTimeHours()));EditText minutes=numberField(R.string.playtime_minutes_label,Integer.toString(document.playTimeMinutesPart()));EditText seconds=numberField(R.string.playtime_seconds_label,Integer.toString(document.playTimeSecondsPart()));form.addView(hours);form.addView(minutes);form.addView(seconds);new AlertDialog.Builder(this).setTitle(R.string.playtime_title).setView(form).setNegativeButton(R.string.close,null).setPositiveButton(android.R.string.ok,(d,w)->{try{document.setPlayTimeComponents(Integer.parseInt(hours.getText().toString()),Integer.parseInt(minutes.getText().toString()),Integer.parseInt(seconds.getText().toString()));persistApplied();}catch(Exception e){Toast.makeText(this,R.string.invalid_number,Toast.LENGTH_SHORT).show();}}).show(); }
    private void editRewards() {
        String[] actions=getResources().getStringArray(R.array.reward_actions);
        new AlertDialog.Builder(this).setTitle(R.string.rewards_title).setItems(actions,(d,index)->{
            if(index<2)requestIndex(R.string.reward_id_label,document.knownUserRankRewardCount(),id->{try{document.setEligibleUserRankRewardClaimed(id,index==0);persistApplied();}catch(IllegalArgumentException error){Toast.makeText(this,R.string.reward_not_unlocked,Toast.LENGTH_LONG).show();}});
            else {document.fixUserRankRewards();persistApplied();}
        }).setNegativeButton(R.string.close,null).show();
    }
    private void editStory() {
        String[] actions=getResources().getStringArray(R.array.story_actions);
        new AlertDialog.Builder(this).setTitle(R.string.story_title).setItems(actions,(d,choice)->{
            if(choice==0){document.clearTutorial();persistApplied();}
            else if(choice==1){for(int chapter=0;chapter<document.storyChapterCount();chapter++)document.clearStoryChapter(chapter,true);persistApplied();}
            else if(choice==5){document.enableFilibusterStage(new java.util.Random().nextInt(48));persistApplied();}
            else chooseStoryChapter(chapter->{
                if(choice==2)requestIndex(R.string.stage_id_label,document.storyStageCount(),stage->editNumberText(getString(R.string.clear_times_label),document.storyClearTimes(chapter,stage),v->document.setStoryClearTimes(chapter,stage,v)));
                else {document.clearStoryChapter(chapter,choice==3);persistApplied();}
            });
        }).setNegativeButton(R.string.close,null).show();
    }
    private void editTreasuresAndAku() {
        String[] actions=getResources().getStringArray(R.array.treasure_aku_actions);
        new AlertDialog.Builder(this).setTitle(R.string.treasure_aku_title).setItems(actions,(d,choice)->runFieldAction(()->{
            if(choice<2)chooseStoryChapter(chapter->{
                if(choice==0)requestIndex(R.string.stage_id_label,document.storyStageCount(),stage->editNumberText(getString(R.string.treasure_grade_label),document.storyTreasure(chapter,stage),v->document.setStoryTreasure(chapter,stage,v)));
                else editNumberText(getString(R.string.treasure_grade_label),3,v->{document.setStoryChapterTreasures(chapter,v);});
            }); else if(choice==2){for(int chapter=0;chapter<document.storyChapterCount();chapter++)document.setStoryChapterTreasures(chapter,3);persistApplied();}else if(choice==3)editOutbreaks();else if(choice==4){document.unlockAkuRealm();persistApplied();}else editAkuProgress();
        })).setNegativeButton(R.string.close,null).show();
    }
    private void chooseStoryChapter(IndexChange change) {
        String[] names=getResources().getStringArray(R.array.story_chapter_names);
        int count=Math.min(document.storyChapterCount(),names.length);
        String[] shown=Arrays.copyOf(names,count);
        new AlertDialog.Builder(this).setTitle(R.string.chapter_id_label).setItems(shown,(dialog,chapter)->change.apply(chapter)).setNegativeButton(R.string.close,null).show();
    }
    private void editOutbreaks() { int chapters=document.outbreakChapterCount();String[] chapterRows=new String[chapters];for(int i=0;i<chapters;i++)chapterRows[i]=getString(R.string.outbreak_chapter_row,document.outbreakChapterId(i),document.outbreakStageCount(i));new AlertDialog.Builder(this).setTitle(R.string.outbreak_chapter_label).setItems(chapterRows,(dialog,chapter)->{String[] actions=getResources().getStringArray(R.array.outbreak_actions);new AlertDialog.Builder(this).setTitle(getString(R.string.outbreak_chapter_title,document.outbreakChapterId(chapter))).setItems(actions,(d,i)->{if(i==0)editOutbreakStage(chapter);else{document.setOutbreakChapterCleared(chapter,i==1);persistApplied();}}).setNegativeButton(R.string.close,null).show();}).setNegativeButton(R.string.close,null).show(); }
    private void editOutbreakStage(int chapter) { int stages=document.outbreakStageCount(chapter);String[] rows=new String[stages];for(int i=0;i<stages;i++)rows[i]=getString(R.string.outbreak_stage_row,document.outbreakStageId(chapter,i),getString(document.outbreakCleared(chapter,i)?R.string.state_cleared:R.string.state_uncleared));new AlertDialog.Builder(this).setTitle(R.string.outbreak_stage_label).setItems(rows,(d,stage)->{document.setOutbreakCleared(chapter,stage,!document.outbreakCleared(chapter,stage));persistApplied();}).setNegativeButton(R.string.close,null).show(); }
    private void editAkuProgress() {
        if(document.akuChapterCount()==0||document.akuStarCount()==0){Toast.makeText(this,R.string.no_aku_data,Toast.LENGTH_SHORT).show();return;}
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(24),dp(8),dp(24),0);EditText progress=numberField(R.string.aku_progress_label,"0");EditText clears=numberField(R.string.clear_times_label,"1");form.addView(progress);form.addView(clears);
        new AlertDialog.Builder(this).setTitle(R.string.aku_title).setView(form).setNegativeButton(R.string.close,null).setPositiveButton(android.R.string.ok,(d,w)->{try{document.setAkuProgress(Integer.parseInt(progress.getText().toString()),Integer.parseInt(clears.getText().toString()));persistApplied();}catch(Exception e){showFieldError(e);}}).show();
    }
    private void editMissions() {
        int[] ids=document.missionIds();String[] actions=getResources().getStringArray(R.array.mission_actions);
        new AlertDialog.Builder(this).setTitle(getString(R.string.missions_title,ids.length)).setItems(actions,(d,choice)->requestMissionId(ids,id->{document.setMissionCompletion(id,choice==0?2:choice==1?4:0);persistApplied();})).setNegativeButton(R.string.close,null).show();
    }
    private void editChallengeAndDojo() {
        String[] choices=getResources().getStringArray(R.array.challenge_actions);
        new AlertDialog.Builder(this).setTitle(R.string.challenge_dojo_title).setItems(choices,(d,index)->{
            if(index==0)editNumber(R.string.challenge_score_label,document.challengeScore(),document::setChallengeScore);
            else if(index==1)editNumber(R.string.dojo_score_label,document.dojoScore(),document::setDojoScore);
            else if(index==2)editTimedScore();
            else editStageMap(SaveDocument.StageMap.DOJO,choices[index]);
        }).setNegativeButton(R.string.close,null).show();
    }
    private void editTimedScore() { requestIndex(R.string.chapter_id_label,document.timedScoreChapterCount(),chapter->requestIndex(R.string.stage_id_label,document.timedScoreStageCount(),stage->editNumberText(getString(R.string.timed_score_label),document.timedScore(chapter,stage),v->document.setTimedScore(chapter,stage,v)))); }
    private void editEnigmaAndGauntlets() { String[] actions=getResources().getStringArray(R.array.enigma_gauntlet_actions);new AlertDialog.Builder(this).setTitle(R.string.enigma_gauntlet_title).setItems(actions,(d,i)->{if(i==0)editNumberText(actions[i],0,id->document.addActiveEnigmaStage(id,System.currentTimeMillis()/1000L));else if(i==1){document.clearActiveEnigmaStages();persistApplied();}else if(i==2)editStageMap(SaveDocument.StageMap.GAUNTLETS,actions[i]);else if(i==3)editStageMap(SaveDocument.StageMap.COLLAB_GAUNTLETS,actions[i]);else editStageMap(SaveDocument.StageMap.ENIGMA_CLEARS,actions[i]);}).setNegativeButton(R.string.close,null).show(); }
    private EditText numberField(int hint,String value) { EditText field=new EditText(this);field.setHint(hint);field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);field.setText(value);return field; }
    private void editMapChoice(SaveDocument.StageMap[] maps,int namesResource) {
        String[] names=getResources().getStringArray(namesResource);
        new AlertDialog.Builder(this).setTitle(R.string.map_editor_title).setItems(names,(d,index)->runFieldAction(()->editStageMap(maps[index],names[index]))).setNegativeButton(R.string.close,null).show();
    }
    private void editEventMaps() {
        String[] names=getResources().getStringArray(R.array.event_map_types);
        int[] first={1,0,0},last={48,435,272},storageType={0,1,2};
        new AlertDialog.Builder(this).setTitle(R.string.event_maps_name).setItems(names,(d,index)->chooseStageMapScope(SaveDocument.StageMap.EVENT,names[index],first[index],last[index],storageType[index]*500)).setNegativeButton(R.string.close,null).show();
    }
    private void editStageMap(SaveDocument.StageMap type,String name) {
        int maps=document.stageMapCount(type);if(maps==0){Toast.makeText(this,R.string.no_map_data,Toast.LENGTH_SHORT).show();return;}
        chooseStageMapScope(type,name,0,maps-1,0);
    }
    private void chooseStageMapScope(SaveDocument.StageMap type,String name,int min,int max,int mapBase) {
        if(max<min){Toast.makeText(this,R.string.no_map_data,Toast.LENGTH_SHORT).show();return;}
        String[] actions=getResources().getStringArray(R.array.map_scope_actions);
        new AlertDialog.Builder(this).setTitle(name).setItems(actions,(d,choice)->{
            if(choice==0)requestNumberRange(R.string.map_id_label,min,max,id->editStageMapAt(type,mapBase+id,name));
            else if(choice==1)editStageMapBatch(type,name,min,max,mapBase);
            else chooseBatchCrownCount(type,name,mapBase+min,mapBase+max);
        }).setNegativeButton(R.string.close,null).show();
    }
    private void editStageMapAt(SaveDocument.StageMap type,int map,String name) {
            String[] actions=getResources().getStringArray(R.array.stage_map_actions);
            new AlertDialog.Builder(this).setTitle(name).setItems(actions,(d,choice)->{
                if(choice==0)requestIndex(R.string.star_id_label,document.stageMapStarCount(type,map),star->requestIndex(R.string.stage_id_label,document.stageMapStageCount(type,map,star),stage->editNumberText(getString(R.string.clear_times_label),document.stageMapClearTimes(type,map,star,stage),v->document.setStageMapClearTimes(type,map,star,stage,v))));
                else if(choice==1)chooseMapCrownCount(type,map,name);
                else runFieldAction(()->{if(usesExpandedCrownRange(type,map))document.clearStageMap(type,map,false,document.stageMapMaxStarCount(type,map));else document.clearStageMap(type,map,false);persistApplied();});
            }).setNegativeButton(R.string.close,null).show();
    }
    private boolean usesExpandedCrownRange(SaveDocument.StageMap type,int map) { return type==SaveDocument.StageMap.UNCANNY||(type==SaveDocument.StageMap.EVENT&&map>=1&&map<=48); }
    private int completionCrownLimit(SaveDocument.StageMap type,int map) {
        if(usesExpandedCrownRange(type,map))return document.stageMapMaxStarCount(type,map);
        return document.stageMapStarCount(type,map);
    }
    private void chooseMapCrownCount(SaveDocument.StageMap type,int map,String name) {
        int maximum;
        try { maximum=completionCrownLimit(type,map); } catch(RuntimeException error){showFieldError(error);return;}
        if(maximum<=0){Toast.makeText(this,R.string.no_map_data,Toast.LENGTH_SHORT).show();return;}
        String[] options=new String[maximum];for(int i=0;i<maximum;i++)options[i]=getString(R.string.crown_count_option,i+1);
        new AlertDialog.Builder(this).setTitle(getString(R.string.crown_count_title)+" · "+name).setItems(options,(d,index)->runFieldAction(()->{document.clearStageMap(type,map,true,index+1);persistApplied();})).setNegativeButton(R.string.close,null).show();
    }
    private void editStageMapBatch(SaveDocument.StageMap type,String name,int min,int max,int mapBase) {
        String[] actions=getResources().getStringArray(R.array.stage_map_batch_actions);
        new AlertDialog.Builder(this).setTitle(getString(R.string.batch_map_title,name)).setItems(actions,(d,choice)->requestBatchMapRange(name,min,max,(first,last)->{
            int actualFirst=mapBase+first,actualLast=mapBase+last;
            if(choice==0)chooseBatchCrownCount(type,name,actualFirst,actualLast);
            else runFieldAction(()->{if(usesExpandedCrownRange(type,actualFirst)){int crowns=completionCrownRangeLimit(type,actualFirst,actualLast);document.clearStageMaps(type,actualFirst,actualLast,false,crowns);}else document.clearStageMaps(type,actualFirst,actualLast,false);persistApplied();});
        })).setNegativeButton(R.string.close,null).show();
    }
    private interface MapRangeChange { void apply(int first,int last); }
    private void requestBatchMapRange(String name,int min,int max,MapRangeChange change) {
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(24),dp(8),dp(24),0);
        EditText first=numberField(R.string.batch_start_map,Integer.toString(min));EditText last=numberField(R.string.batch_end_map,Integer.toString(max));form.addView(first);form.addView(last);
        new AlertDialog.Builder(this).setTitle(getString(R.string.batch_map_title,name)).setView(form).setNegativeButton(R.string.close,null).setPositiveButton(android.R.string.ok,(d,w)->{
            try { int from=Integer.parseInt(first.getText().toString().trim()),to=Integer.parseInt(last.getText().toString().trim());if(from<min||to>max||from>to)throw new NumberFormatException();change.apply(from,to); }
            catch(NumberFormatException error){Toast.makeText(this,R.string.invalid_map_range,Toast.LENGTH_SHORT).show();}
            catch(RuntimeException error){showFieldError(error);}
        }).show();
    }
    private void chooseBatchCrownCount(SaveDocument.StageMap type,String name,int firstMap,int lastMap) {
        int maximum;
        try { maximum=completionCrownRangeLimit(type,firstMap,lastMap); }
        catch(RuntimeException error){showFieldError(error);return;}
        if(maximum<=0){Toast.makeText(this,R.string.no_batch_map_data,Toast.LENGTH_SHORT).show();return;}
        String[] options=new String[maximum];for(int i=0;i<maximum;i++)options[i]=getString(R.string.crown_count_option,i+1);
        new AlertDialog.Builder(this).setTitle(getString(R.string.crown_count_title)+" · "+name).setItems(options,(d,index)->runFieldAction(()->{if(type==SaveDocument.StageMap.ZERO_LEGENDS)document.clearStageMapsUpToConfiguredCrowns(type,firstMap,lastMap,true,index+1);else document.clearStageMaps(type,firstMap,lastMap,true,index+1);persistApplied();})).setNegativeButton(R.string.close,null).show();
    }
    private int completionCrownRangeLimit(SaveDocument.StageMap type,int firstMap,int lastMap) {
        if(type==SaveDocument.StageMap.ZERO_LEGENDS){int maximum=0;for(int map=firstMap;map<=lastMap;map++)maximum=Math.max(maximum,completionCrownLimit(type,map));return maximum;}
        int maximum=16;for(int map=firstMap;map<=lastMap;map++)maximum=Math.min(maximum,completionCrownLimit(type,map));return maximum;
    }
    private void editGamatoto() {
        String[] actions=getResources().getStringArray(R.array.gamatoto_actions);
        new AlertDialog.Builder(this).setTitle(R.string.gamatoto_title).setItems(actions,(d,index)->runFieldAction(()->{
            if(index==0)editNumber(R.string.gamatoto_level_label,document.gamatotoLevel(),document::setGamatotoLevel);
            else if(index==1)editNumber(R.string.gamatoto_xp_label,document.gamatotoXp(),document::setGamatotoXp);
            else editGamatotoHelpers();
        })).setNegativeButton(R.string.close,null).show();
    }
    private void editGamatotoHelpers() { int[] current=document.gamatotoHelperRarityAmounts();String[] names=getResources().getStringArray(R.array.gamatoto_helper_rarities);LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(24),dp(8),dp(24),0);EditText[] fields=new EditText[names.length];for(int i=0;i<names.length;i++){fields[i]=numberField(R.string.helper_amount_label,Integer.toString(current[i]));fields[i].setHint(names[i]);form.addView(fields[i]);}new AlertDialog.Builder(this).setTitle(R.string.gamatoto_helpers_title).setView(form).setNegativeButton(R.string.close,null).setPositiveButton(android.R.string.ok,(d,w)->{try{int[] amounts=new int[fields.length];for(int i=0;i<fields.length;i++)amounts[i]=Integer.parseInt(fields[i].getText().toString());document.setGamatotoHelperRarityAmounts(amounts);persistApplied();}catch(Exception e){Toast.makeText(this,R.string.invalid_helper_amounts,Toast.LENGTH_LONG).show();}}).show(); }
    private void editOtoto() {
        String[] actions=getResources().getStringArray(R.array.ototo_actions);
        new AlertDialog.Builder(this).setTitle(R.string.ototo_title).setItems(actions,(d,index)->runFieldAction(()->{
            if(index==0)editBaseMaterials();
            else if(index==1)editNumber(R.string.engineers_label,document.ototoEngineers(),document::setOtotoEngineers);
            else editCannon();
        })).setNegativeButton(R.string.close,null).show();
    }
    private void editBaseMaterials() { int count=document.baseMaterialCount();String[] names=getResources().getStringArray(R.array.base_material_names);String[] rows=new String[count];for(int i=0;i<count;i++){String name=i<names.length?names[i]:getString(R.string.material_id_label)+" "+i;rows[i]=getString(R.string.material_row,name,i,document.baseMaterial(i));}new AlertDialog.Builder(this).setTitle(R.string.material_amount_label).setItems(rows,(d,index)->editNumberText(rows[index],document.baseMaterial(index),v->document.setBaseMaterial(index,v))).setNegativeButton(R.string.close,null).setNeutralButton(R.string.material_set_all,(d,w)->editNumberText(getString(R.string.material_set_all),document.baseMaterial(0),v->{for(int i=0;i<count;i++)document.setBaseMaterial(i,v);})).show(); }
    private void editCannon() {
        String[] cannonNames=getResources().getStringArray(R.array.cannon_names),developmentNames=getResources().getStringArray(R.array.cannon_development_names);
        String[] rows=new String[document.cannonCount()];for(int i=0;i<rows.length;i++){int id=document.cannonId(i),development=document.cannonDevelopment(i);String name=id<cannonNames.length?cannonNames[id]:getString(R.string.cannons_title);String developmentName=development>=0&&development<developmentNames.length?developmentNames[development]:Integer.toString(development);rows[i]=id==0?getString(R.string.cannon_row_parts,name,id):getString(R.string.cannon_row,name,id,developmentName);}
        new AlertDialog.Builder(this).setTitle(R.string.cannons_title).setItems(rows,(d,index)->{
            int cannonId=document.cannonId(index),parts=document.cannonPartCount(index);int optionCount=parts+(cannonId==0?0:1);String[] options=new String[optionCount];String[] partNames=getResources().getStringArray(R.array.cannon_part_names);
            int offset=0;
            if(cannonId!=0) options[offset++]=getString(R.string.cannon_development_label);
            for(int p=0;p<parts;p++){int nameIndex=cannonId==0?0:1+(cannonId-1)*3+p;String partName=nameIndex<partNames.length?partNames[nameIndex]:getString(R.string.cannon_development_label);options[offset+p]=getString(R.string.cannon_part_row,partName,p,document.cannonPartLevel(index,p)+(p==0?1:0));}
            new AlertDialog.Builder(this).setTitle(rows[index]).setItems(options,(x,choice)->{
                if(cannonId!=0&&choice==0) editNumberText(options[0],document.cannonDevelopment(index),v->document.setCannonDevelopment(index,v));
                else { int part=cannonId==0?choice:choice-1;int nameIndex=cannonId==0?0:1+(cannonId-1)*3+part;String partName=nameIndex<partNames.length?partNames[nameIndex]:getString(R.string.cannon_development_label);int displayLevel=document.cannonPartLevel(index,part)+(part==0?1:0);editNumberText(getString(R.string.cannon_part_label,partName,part),displayLevel,v->document.setCannonPartLevel(index,part,v-(part==0?1:0))); }
            }).show();
        }).setNegativeButton(R.string.close,null).show();
    }
    private void editCatShrine() {
        String[] actions=getResources().getStringArray(R.array.shrine_actions);
        new AlertDialog.Builder(this).setTitle(R.string.shrine_title).setItems(actions,(d,index)->{
            if(index==0)editNumber(R.string.shrine_level_label,document.catShrineLevel(),document::setCatShrineLevel);
            else if(index==1)editLong(R.string.shrine_xp_label,document.catShrineXp(),document::setCatShrineXp);
            else {document.setCatShrineGone(index==3);persistApplied();}
        }).setNegativeButton(R.string.close,null).show();
    }
    private void editLineups() { editNumber(R.string.unlocked_lineups_label,document.unlockedLineups(),document::setUnlockedLineups); }
    private void editLineupsAndGambling() { String[] actions=getResources().getStringArray(R.array.lineup_gambling_actions);new AlertDialog.Builder(this).setTitle(R.string.lineup_gambling_title).setItems(actions,(d,i)->{if(i==0)editLineups();else{document.resetGambling(SaveDocument.GamblingTable.WILDCAT_SLOTS);document.resetGambling(SaveDocument.GamblingTable.CAT_SCRATCHER);persistApplied();}}).setNegativeButton(R.string.close,null).show(); }
    private void editGambling() { String[] tables=getResources().getStringArray(R.array.gambling_tables);new AlertDialog.Builder(this).setTitle(R.string.gambling_title).setItems(tables,(d,i)->editGamblingTable(i==0?SaveDocument.GamblingTable.WILDCAT_SLOTS:SaveDocument.GamblingTable.CAT_SCRATCHER,tables[i])).setNegativeButton(R.string.close,null).show(); }
    private void editGamblingTable(SaveDocument.GamblingTable type,String titleText) { String[] actions=getResources().getStringArray(R.array.gambling_actions);new AlertDialog.Builder(this).setTitle(titleText).setItems(actions,(d,i)->{if(i==0)addGamblingStart(type);else if(i==1)chooseGamblingStart(type,false);else if(i==2)chooseGamblingStart(type,true);else{document.resetGambling(type);persistApplied();}}).setNegativeButton(R.string.close,null).show(); }
    private void addGamblingStart(SaveDocument.GamblingTable type) { LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(24),dp(8),dp(24),0);EditText id=numberField(R.string.gambling_event_id,"0");EditText date=numberField(R.string.gambling_date_label,"20260101");form.addView(id);form.addView(date);new AlertDialog.Builder(this).setTitle(R.string.gambling_add).setView(form).setNegativeButton(R.string.close,null).setPositiveButton(android.R.string.ok,(d,w)->{try{document.addGamblingStart(type,Integer.parseInt(id.getText().toString()),Integer.parseInt(date.getText().toString()));persistApplied();}catch(Exception e){Toast.makeText(this,R.string.invalid_number,Toast.LENGTH_SHORT).show();}}).show(); }
    private void chooseGamblingStart(SaveDocument.GamblingTable type,boolean remove) { int count=document.gamblingStartCount(type);if(count==0){Toast.makeText(this,R.string.no_gambling_records,Toast.LENGTH_SHORT).show();return;}String[] rows=new String[count];for(int i=0;i<count;i++)rows[i]=getString(R.string.gambling_row,document.gamblingStartKey(type,i),document.gamblingStartDate(type,i));new AlertDialog.Builder(this).setTitle(R.string.gambling_title).setItems(rows,(d,i)->{if(remove){document.removeGamblingStart(type,i);persistApplied();}else editNumberText(rows[i],document.gamblingStartDate(type,i),v->document.setGamblingStartDate(type,i,v));}).setNegativeButton(R.string.close,null).show(); }
    private void editPassAndRestart() { String[] actions=getResources().getStringArray(R.array.pass_restart_actions);new AlertDialog.Builder(this).setTitle(R.string.pass_restart_title).setItems(actions,(d,i)->{if(i==0)editNumberText(actions[i],Math.max(1,document.goldPassOfficerId()),v->document.grantGoldPass(v,System.currentTimeMillis()/1000L,30));else if(i==1){document.removeGoldPass();persistApplied();}else{document.setRestartPackState(1);persistApplied();}}).setNegativeButton(R.string.close,null).show(); }
    private interface LongChange { void apply(long value); }
    private void editLong(int title,long current,LongChange change) { EditText field=new EditText(this);field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);field.setText(String.valueOf(current));new AlertDialog.Builder(this).setTitle(title).setView(field).setNegativeButton(R.string.close,null).setPositiveButton(android.R.string.ok,(d,w)->{try{change.apply(Long.parseLong(field.getText().toString()));workingCopy=document.toBytes();persistSession(false,getString(title));}catch(NumberFormatException e){Toast.makeText(this,R.string.invalid_number,Toast.LENGTH_SHORT).show();}}).show(); }
    private void editLongText(String title,long current,LongChange change) { EditText field=new EditText(this);field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);field.setText(String.valueOf(current));new AlertDialog.Builder(this).setTitle(title).setView(field).setNegativeButton(R.string.close,null).setPositiveButton(android.R.string.ok,(d,w)->{try{change.apply(Long.parseLong(field.getText().toString()));workingCopy=document.toBytes();persistSession(false,title);}catch(NumberFormatException e){Toast.makeText(this,R.string.invalid_number,Toast.LENGTH_SHORT).show();}}).show(); }
    private void requestMissionId(int[] ids,IndexChange change) { EditText field=new EditText(this);field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);field.setHint(R.string.mission_id_hint);new AlertDialog.Builder(this).setTitle(R.string.mission_id_label).setView(field).setNegativeButton(R.string.close,null).setPositiveButton(android.R.string.ok,(d,w)->{try{int id=Integer.parseInt(field.getText().toString());boolean found=false;for(int value:ids)if(value==id){found=true;break;}if(!found)throw new NumberFormatException();change.apply(id);}catch(NumberFormatException e){Toast.makeText(this,R.string.invalid_mission_id,Toast.LENGTH_SHORT).show();}}).show(); }
    private void requestEnemyGuideId(IndexChange change) { EditText field=new EditText(this);field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);field.setHint(getString(R.string.enemy_id_hint,2,document.enemyGuideCount()+1));new AlertDialog.Builder(this).setTitle(R.string.enemy_id_label).setView(field).setNegativeButton(R.string.close,null).setPositiveButton(android.R.string.ok,(d,w)->{try{int displayed=Integer.parseInt(field.getText().toString());if(displayed<2||displayed>document.enemyGuideCount()+1)throw new NumberFormatException();change.apply(displayed-2);}catch(NumberFormatException e){Toast.makeText(this,R.string.invalid_number,Toast.LENGTH_SHORT).show();}}).show(); }
    private void runFieldAction(Runnable action) { try{action.run();}catch(RuntimeException error){showFieldError(error);} }
    private interface IndexChange { void apply(int index); }
    private void requestIndex(int title,int count,IndexChange change) { if(count<=0){showFieldError();return;}EditText field=new EditText(this);field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);field.setHint(getString(R.string.index_range,count-1));new AlertDialog.Builder(this).setTitle(title).setView(field).setNegativeButton(R.string.close,null).setPositiveButton(android.R.string.ok,(d,w)->{try{int value=Integer.parseInt(field.getText().toString());if(value<0||value>=count)throw new NumberFormatException();change.apply(value);}catch(NumberFormatException e){Toast.makeText(this,R.string.invalid_number,Toast.LENGTH_SHORT).show();}catch(RuntimeException e){showFieldError();}}).show(); }
    private void requestNumberRange(int title,int min,int max,IndexChange change) { EditText field=new EditText(this);field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);field.setHint(getString(R.string.number_range,min,max));new AlertDialog.Builder(this).setTitle(title).setView(field).setNegativeButton(R.string.close,null).setPositiveButton(android.R.string.ok,(d,w)->{try{int value=Integer.parseInt(field.getText().toString());if(value<min||value>max)throw new NumberFormatException();change.apply(value);}catch(NumberFormatException e){Toast.makeText(this,R.string.invalid_number,Toast.LENGTH_SHORT).show();}catch(RuntimeException e){showFieldError();}}).show(); }
    private void persistApplied() { workingCopy=document.toBytes();persistSession(false,getString(R.string.history_batch_edit));Toast.makeText(this,R.string.edit_applied,Toast.LENGTH_SHORT).show(); }
    private void editTreasureGroup() {
        if (!document.hasItemProfile()) { unsupportedVersion(); return; }
        String[] groups=getResources().getStringArray(R.array.treasure_groups);
        new AlertDialog.Builder(this).setTitle(R.string.treasure_items_title).setItems(groups,(d,i)->{
            if(i==0)editArray(R.string.treasure_chests_title,R.string.treasure_chest_label,document.treasureChests(),document::setTreasureChest);
            else editNumber(R.string.hundred_million_ticket_label,document.hundredMillionTicket(),document::setHundredMillionTicket);
        }).setNegativeButton(R.string.close,null).show();
    }
    private void editArray(int title,int itemLabel,int[] values,IndexedNumberChange change) {
        if (!document.hasItemProfile()) { unsupportedVersion(); return; }
        String[] rows=new String[values.length]; for(int i=0;i<values.length;i++){String name=itemName(title,i,itemLabel);rows[i]=name+" (ID "+i+"): "+values[i];}
        new AlertDialog.Builder(this).setTitle(title).setItems(rows,(d,index)->editNumberText(itemName(title,index,itemLabel)+" (ID "+index+")",values[index],value->change.apply(index,value))).setNegativeButton(R.string.close,null).show();
    }
    private String itemName(int title,int index,int fallback) { int array=title==R.string.battle_items_title?R.array.battle_item_names:title==R.string.catseyes_title?R.array.catseye_names:title==R.string.catfruit_title?R.array.catfruit_names:title==R.string.catamins_title?R.array.catamin_names:0;if(array!=0){String[] names=getResources().getStringArray(array);if(index<names.length)return names[index];}return getString(fallback,index+1); }
    private void editNumber(int label, int current, NumberChange change) {
        editNumberText(getString(label), current, change);
    }
    private void editNumberText(String label, int current, NumberChange change) {
        EditText field = new EditText(this); field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED); field.setText(String.valueOf(current));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(label).setView(field).setNegativeButton(R.string.close, null).setPositiveButton(android.R.string.ok, (window, which) -> {
            final int value;
            try { value=Integer.parseInt(field.getText().toString().trim()); } catch (NumberFormatException ignored) { Toast.makeText(this,R.string.invalid_number,Toast.LENGTH_SHORT).show();return; }
            try { change.apply(value); } catch (IllegalArgumentException error) { reportError("editor-value", error); Toast.makeText(this,R.string.invalid_number,Toast.LENGTH_SHORT).show();return; } catch (RuntimeException error) { showFieldError(error);return; }
            try { workingCopy=document.toBytes();persistSession(false,label); } catch (RuntimeException error) { showFieldError(error); }
        }).create();
        dialog.setOnShowListener(ignored -> field.post(() -> {
            field.requestFocus();
            android.view.inputmethod.InputMethodManager keyboard = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }));
        dialog.show();
    }
    private void showFieldError() { showFieldError(new IllegalStateException("Save field parsing failed")); }
    private void showFieldError(Throwable error) {
        reportError("editor-field", error);
        Toast.makeText(this,R.string.field_parse_failed,Toast.LENGTH_LONG).show();
    }

    private void captureError(String operation, Throwable error) {
        captureError(operation, error, document == null ? workingCopy : document.toBytes());
    }

    private void captureError(String operation, Throwable error, byte[] save) {
        DebugReporter.record(operation, error, save);
    }

    private void reportError(String operation, Throwable error) {
        reportError(operation, error, document == null ? workingCopy : document.toBytes());
    }

    private void reportError(String operation, Throwable error, byte[] save) {
        captureError(operation, error, save);
        if (!activityActive()) return;
        runOnUiThread(this::offerErrorReport);
    }

    private void offerErrorReport() {
        if (!activityActive() || errorReportShowing) return;
        errorReportShowing = true;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.debug_error_title)
                .setMessage(R.string.debug_error_message)
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.debug_copy_log, (d, which) -> copyDebugReport())
                .create();
        dialog.setOnDismissListener(ignored -> errorReportShowing = false);
        dialog.show();
    }

    private void copyDebugReport() {
        Executors.newSingleThreadExecutor().execute(() -> {
            String report = DebugReporter.report();
            runOnUiThread(() -> {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.debug_copy_log), report));
                Toast.makeText(this, R.string.debug_log_copied, Toast.LENGTH_LONG).show();
            });
        });
    }
    private void editString(String label,String current,StringChange change) { EditText field=new EditText(this);field.setSingleLine(true);field.setText(current);new AlertDialog.Builder(this).setTitle(label).setView(field).setNegativeButton(R.string.close,null).setPositiveButton(android.R.string.ok,(d,w)->{try{change.apply(field.getText().toString());workingCopy=document.toBytes();persistSession(false,label);}catch(Exception e){Toast.makeText(this,R.string.invalid_credential_length,Toast.LENGTH_LONG).show();}}).show(); }

    private void showHistory() {
        if(sessionId==null){Toast.makeText(this,R.string.history_restore_failed,Toast.LENGTH_SHORT).show();return;}
        try {
            SessionStore.History history=sessionStore.history(sessionId);String[] rows=new String[history.entries.size()];DateFormat format=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT);
            for(int i=0;i<rows.length;i++){SessionStore.HistoryEntry entry=history.entries.get(i);String label=entry.initial?getString(R.string.history_initial):historyDisplayLabel(entry.label);String current=i==history.currentIndex?getString(R.string.history_current):"";rows[i]=getString(R.string.history_row,format.format(new Date(entry.modifiedAt)),label,current);}
            LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(dp(20),dp(4),dp(20),0);TextView note=new TextView(this);note.setText(R.string.history_limit_note);note.setTextColor(getColor(R.color.muted));note.setPadding(0,0,0,dp(8));layout.addView(note);
            LinearLayout navigation=new LinearLayout(this);navigation.setOrientation(LinearLayout.HORIZONTAL);MaterialButton undo=new MaterialButton(this);undo.setText(R.string.history_undo);MaterialButton redo=new MaterialButton(this);redo.setText(R.string.history_redo);navigation.addView(undo,new LinearLayout.LayoutParams(0,dp(48),1));navigation.addView(redo,new LinearLayout.LayoutParams(0,dp(48),1));layout.addView(navigation);
            ListView list=new ListView(this);list.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,rows));layout.addView(list,new LinearLayout.LayoutParams(-1,dp(320)));
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle(R.string.history_title).setView(layout).setNegativeButton(R.string.close,null).create();
            undo.setEnabled(history.currentIndex>0);redo.setEnabled(history.currentIndex<history.entries.size()-1);undo.setOnClickListener(v->restoreHistoryState(history.currentIndex-1,dialog));redo.setOnClickListener(v->restoreHistoryState(history.currentIndex+1,dialog));list.setOnItemClickListener((parent,row,position,id)->{if(position!=history.currentIndex)restoreHistoryState(position,dialog);});dialog.show();
        } catch(Exception error){reportError("history-list",error);Toast.makeText(this,R.string.history_restore_failed,Toast.LENGTH_LONG).show();}
    }
    private void restoreHistoryState(int index,AlertDialog dialog) {
        try {SessionStore.Session restored=sessionStore.restoreHistory(sessionId,index);document=SaveDocument.open(restored.save);workingCopy=restored.save;openedName=restored.name;accountPassword=restored.password;dialog.dismiss();showEditor();refreshSessionList();Toast.makeText(this,R.string.history_restored,Toast.LENGTH_SHORT).show();}
        catch(Exception error){reportError("history-restore",error);Toast.makeText(this,R.string.history_restore_failed,Toast.LENGTH_LONG).show();}
    }
    private String historyDisplayLabel(String label) {
        if(label!=null&&label.startsWith("feature:")){try{int id=Integer.parseInt(label.substring(8));String[] features=getResources().getStringArray(R.array.feature_names);if(id>=0&&id<features.length)return features[id];}catch(NumberFormatException ignored){}}
        if("batch".equals(label))return getString(R.string.history_batch_edit);if("edit".equals(label))return getString(R.string.history_generic_edit);return label==null||label.isEmpty()?getString(R.string.history_generic_edit):label;
    }
    private String stableHistoryLabel(String requested) { if(activeFeatureId>=0)return "feature:"+activeFeatureId;return "batch".equals(requested)?"batch":"edit"; }

    private void showAbout() {
        screenBeforeAbout = screen;
        screen = Screen.ABOUT;
        title.setText(R.string.about);
        View view = inflate(R.layout.screen_about);
        ((TextView) view.findViewById(R.id.version)).setText(getString(R.string.version_format, BuildConfig.VERSION_NAME));
        view.findViewById(R.id.androidRepo).setOnClickListener(v -> openUrl("https://github.com/tuxKOH/BCSFE-Android"));
        view.findViewById(R.id.pythonRepo).setOnClickListener(v -> openUrl("https://github.com/fieryhenry/BCSFE-Python"));
        view.findViewById(R.id.languageButton).setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(R.string.choose_language)
                .setItems(R.array.language_options,(d,index)->AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(index==0?"en":"zh-CN"))).show());
    }

    private void openUrl(String url) { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
    private void toggleSessions(){if(sessionPanel.getVisibility()==View.VISIBLE)hideSessions();else showSessions();}
    private void showSessions(){refreshSessionList();sessionPanel.animate().cancel();sessionPanel.setVisibility(View.VISIBLE);sessionPanel.setAlpha(0f);sessionPanel.setTranslationX(-dp(48));sessionPanel.animate().alpha(1f).translationX(0f).setDuration(220).start();}
    private void hideSessions(){sessionPanel.animate().cancel();sessionPanel.animate().alpha(0f).translationX(-dp(48)).setDuration(180).withEndAction(()->{sessionPanel.setVisibility(View.GONE);sessionPanel.setAlpha(1f);sessionPanel.setTranslationX(0f);}).start();}
    private void startNewSession(){if(!persistSession(true))return;sessionId=null;document=null;workingCopy=null;openedName=null;accountPassword=null;insideCategory=false;hideSessions();showHome();}
    private void configureSessionList(){
        sessionList.setOnItemClickListener((parent,row,position,id)->{try{List<SessionStore.Session> sessions=sessionStore.list();if(position>=sessions.size())return;openSession(sessions.get(position));hideSessions();}catch(Exception error){Toast.makeText(this,R.string.session_switch_failed,Toast.LENGTH_LONG).show();}});
        sessionList.setOnItemLongClickListener((parent,row,position,id)->{try{List<SessionStore.Session> sessions=sessionStore.list();if(position<sessions.size())showSessionActions(sessions.get(position));}catch(Exception error){Toast.makeText(this,R.string.session_switch_failed,Toast.LENGTH_LONG).show();}return true;});
        refreshSessionList();
    }
    private void refreshSessionList(){try{List<SessionStore.Session> sessions=sessionStore.list();String[] rows=new String[sessions.size()];for(int i=0;i<rows.length;i++)rows[i]=sessions.get(i).name+(sessions.get(i).id.equals(sessionId)?getString(R.string.current_suffix):"");sessionList.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,rows));}catch(Exception ignored){sessionList.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,new String[0]));}}
    private void openSession(SessionStore.Session session)throws Exception{if(document!=null&&!persistSession(true))return;SaveDocument replacement=SaveDocument.open(session.save);sessionStore.setCurrent(session.id);sessionId=session.id;document=replacement;workingCopy=session.save;openedName=session.name;accountPassword=session.password;showEditor();refreshSessionList();}
    private void showSessionActions(SessionStore.Session session){String[] actions={getString(R.string.rename_session),getString(R.string.delete_session)};new AlertDialog.Builder(this).setTitle(session.name).setItems(actions,(d,i)->{if(i==0)renameSession(session);else deleteSession(session);}).setNegativeButton(R.string.close,null).show();}
    private void renameSession(SessionStore.Session session){EditText field=new EditText(this);field.setSingleLine(true);field.setText(session.name);new AlertDialog.Builder(this).setTitle(R.string.rename_session).setView(field).setNegativeButton(R.string.close,null).setPositiveButton(android.R.string.ok,(d,w)->{try{sessionStore.rename(session.id,field.getText().toString());SessionStore.Session renamed=sessionStore.load(session.id);if(session.id.equals(sessionId)&&renamed!=null){openedName=renamed.name;title.setText(openedName);}refreshSessionList();}catch(Exception error){Toast.makeText(this,R.string.session_save_failed,Toast.LENGTH_LONG).show();}}).show();}
    private void deleteSession(SessionStore.Session session){new AlertDialog.Builder(this).setTitle(R.string.delete_session).setMessage(R.string.delete_session_confirm).setNegativeButton(R.string.close,null).setPositiveButton(R.string.delete_session,(d,w)->{try{boolean current=session.id.equals(sessionId);sessionStore.delete(session.id);if(current){sessionId=null;document=null;workingCopy=null;openedName=null;accountPassword=null;SessionStore.Session next=sessionStore.load();if(next==null)showHome();else openSession(next);}refreshSessionList();}catch(Exception error){Toast.makeText(this,R.string.session_discard_failed,Toast.LENGTH_LONG).show();}}).show();}
    private boolean persistSession() { return persistSession(false,null); }
    private boolean persistSession(boolean reportFailure) { return persistSession(reportFailure,null); }
    private boolean persistSession(boolean reportFailure,String historyLabel) { if(document==null)return true;try{workingCopy=document.toBytes();if(sessionId==null){SessionStore.Session session=sessionStore.create(workingCopy,openedName,accountPassword);sessionId=session.id;}else sessionStore.save(sessionId,workingCopy,openedName,accountPassword,stableHistoryLabel(historyLabel));refreshSessionList();return true;}catch(Exception error){if(reportFailure){reportError("session-save",error,workingCopy);Toast.makeText(this,R.string.session_save_failed,Toast.LENGTH_LONG).show();}return false;} }
    private boolean restoreSession() { try { SessionStore.Session session=sessionStore.load();if(session==null)return false;sessionId=session.id;workingCopy=session.save;document=SaveDocument.open(workingCopy);openedName=session.name;accountPassword=session.password;showEditor();refreshSessionList();Toast.makeText(this,R.string.session_restored,Toast.LENGTH_SHORT).show();return true;}catch(Exception e){reportError("session-restore",e,workingCopy);return false;} }
    private void confirmExit() { new AlertDialog.Builder(this).setTitle(R.string.exit_confirm_title).setMessage(R.string.exit_confirm_message).setNegativeButton(R.string.close,null).setPositiveButton(R.string.exit_keep,(d,w)->{if(persistSession(true))finishAndRemoveTask();}).setNeutralButton(R.string.discard_session,(d,w)->confirmDiscard()).show(); }
    private void confirmDiscard() { new AlertDialog.Builder(this).setTitle(R.string.discard_session).setMessage(R.string.discard_confirm).setNegativeButton(R.string.close,null).setPositiveButton(R.string.discard_session,(d,w)->{try{if(sessionId!=null)sessionStore.delete(sessionId);sessionId=null;document=null;workingCopy=null;openedName=null;accountPassword=null;SessionStore.Session next=sessionStore.load();if(next==null)showHome();else openSession(next);refreshSessionList();}catch(Exception error){Toast.makeText(this,R.string.session_discard_failed,Toast.LENGTH_LONG).show();}}).show(); }
    private View inflate(int layout) { content.removeAllViews(); View v = LayoutInflater.from(this).inflate(layout, content, false);v.setAlpha(0f);v.setTranslationY(dp(12));content.addView(v);v.animate().alpha(1f).translationY(0f).setDuration(200).start();return v; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private String displayName(Uri uri) {
        try (var cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {String name=cursor.getString(0);if(name!=null&&!name.trim().isEmpty())return name;}
        }
        return "SAVE_DATA";
    }
    private static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b)); return out.toString(); }

    @Override public void onBackPressed() {
        if (screen == Screen.EDITOR && insideCategory) { try { showEditorCategories(editorView); } catch(Exception ignored) {} }
        else if (screen == Screen.EDITOR) confirmExit();
        else if (screen == Screen.ABOUT && screenBeforeAbout == Screen.EDITOR && document != null) {
            try { showEditor(); } catch (Exception error) { showHome(); }
        }
        else if (screen != Screen.HOME) showHome();
        else super.onBackPressed();
    }
    @Override protected void onPause() { persistSession(); super.onPause(); }
}
