package io.github.tuxkoh.bcsfe;

import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import io.github.tuxkoh.bcsfe.core.SaveDocument;
import java.util.Arrays;
import java.util.function.Consumer;

/** Selected-cat UI; mutations and atomicity belong to SaveDocument. */
final class CatBatchEditor {
    private final AppCompatActivity activity;
    private final SaveDocument document;
    private final int[] ids;
    private final String[] names;
    private final Runnable applied;
    private final Consumer<RuntimeException> failed;

    CatBatchEditor(AppCompatActivity activity, SaveDocument document, int[] ids, String[] names,
                   Runnable applied, Consumer<RuntimeException> failed) {
        this.activity = activity; this.document = document;
        this.ids = ids.clone(); this.names = names.clone();
        this.applied = applied; this.failed = failed;
    }

    void show() {
        String first = TextUtils.join(activity.getString(R.string.cat_batch_separator),
                Arrays.copyOf(names, Math.min(2, names.length)));
        TextView title = new TextView(activity);
        int padding = dp(24);
        title.setPadding(padding, dp(16), padding, dp(12));
        title.setText(activity.getString(R.string.cat_batch_title, first, ids.length));
        title.setTextSize(20);
        title.setTextColor(activity.getColor(R.color.primary));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setContentDescription(activity.getString(R.string.cat_batch_show_selection, ids.length));
        title.setOnClickListener(v -> new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.cat_batch_selection, ids.length))
                .setItems(names, null).setNegativeButton(R.string.close, null).show());
        new AlertDialog.Builder(activity).setCustomTitle(title)
                .setItems(R.array.cat_batch_actions, (d, choice) -> {
                    if (choice == 0) new AlertDialog.Builder(activity).setTitle(R.string.cat_batch_unlocked)
                            .setItems(new String[]{activity.getString(R.string.yes), activity.getString(R.string.no)},
                                    (dialog, selected) -> apply(SaveDocument.CatBatchEdit.UNLOCKED, selected == 0 ? 1 : 0))
                            .setNegativeButton(R.string.close, (dialog, which) -> show()).show();
                    else if (choice == 1) level(false);
                    else if (choice == 2) level(true);
                    else if (choice == 3) new AlertDialog.Builder(activity).setTitle(R.string.cat_current_form_title)
                            .setItems(R.array.cat_form_stages, (dialog, selected) -> apply(SaveDocument.CatBatchEdit.CURRENT_FORM, selected))
                            .setNegativeButton(R.string.close, (dialog, which) -> show()).show();
                    else new AlertDialog.Builder(activity).setTitle(R.string.cat_reset_title)
                            .setMessage(activity.getString(R.string.cat_batch_reset_confirm, ids.length))
                            .setNegativeButton(R.string.close, (dialog, which) -> show())
                            .setPositiveButton(R.string.cat_reset_action, (dialog, which) -> apply(SaveDocument.CatBatchEdit.RESET, 0)).show();
                }).setNegativeButton(R.string.close, null).show();
    }

    private void level(boolean plus) {
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setText(Integer.toString(plus ? document.catPlusLevel(ids[0]) : document.catBaseLevel(ids[0])));
        input.selectAll();
        LinearLayout body = new LinearLayout(activity);
        body.setPadding(dp(24), dp(8), dp(24), 0);
        body.addView(input, new LinearLayout.LayoutParams(-1, -2));
        int minimum = plus ? 0 : 1;
        int maximum = plus ? Arrays.stream(ids).map(document::catMaxPlusLevel).min().orElse(0) : 60;
        SaveDocument.CatBatchEdit edit = plus ? SaveDocument.CatBatchEdit.PLUS_LEVEL : SaveDocument.CatBatchEdit.BASE_LEVEL;
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(plus ? R.string.cat_batch_plus : R.string.cat_batch_base).setView(body)
                .setNegativeButton(R.string.close, (d, w) -> show()).setPositiveButton(android.R.string.ok, null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int value;
            try { value = Integer.parseInt(input.getText().toString().trim()); }
            catch (NumberFormatException invalid) { input.setError(activity.getString(R.string.invalid_number)); return; }
            if (value < minimum || value > (plus ? 65535 : 65536)) {
                input.setError(activity.getString(R.string.value_out_of_range)); return;
            }
            if (value > maximum) {
                new AlertDialog.Builder(activity).setMessage(activity.getString(R.string.unsafe_cat_level_warning, minimum, maximum))
                        .setNegativeButton(R.string.close, null)
                        .setPositiveButton(R.string.execute_anyway, (d, w) -> { dialog.dismiss(); apply(edit, value); }).show();
            } else { dialog.dismiss(); apply(edit, value); }
        });
        input.requestFocus();
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }

    private void apply(SaveDocument.CatBatchEdit edit, int value) {
        try { document.editSelectedCats(ids, edit, value); applied.run(); show(); }
        catch (IllegalArgumentException invalid) { Toast.makeText(activity, R.string.value_out_of_range, Toast.LENGTH_SHORT).show(); show(); }
        catch (RuntimeException error) { failed.accept(error); }
    }

    private int dp(int value) { return (int) (value * activity.getResources().getDisplayMetrics().density); }
}
