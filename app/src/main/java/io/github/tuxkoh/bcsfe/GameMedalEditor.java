package io.github.tuxkoh.bcsfe;

import android.text.Editable;
import android.text.TextWatcher;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import io.github.tuxkoh.bcsfe.core.SaveDocument;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.function.Consumer;

/** Searchable IDs, names and requirements from medalname.tsv, including unknown owned IDs. */
final class GameMedalEditor {
    static void show(AppCompatActivity activity, SaveDocument document, boolean add,
                     Runnable applied, Consumer<RuntimeException> failed) {
        String[] names=new GameCatalog(activity,document.region()).medals();
        LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);
        int padding=(int)(24*activity.getResources().getDisplayMetrics().density);
        body.setPadding(padding,padding/3,padding,0);
        EditText search=new EditText(activity);search.setSingleLine(true);search.setHint(R.string.medal_search_hint);
        body.addView(search);
        ListView list=new ListView(activity);body.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        TextView empty=new TextView(activity);empty.setText(add?R.string.no_available_medals:R.string.no_game_medals);
        body.addView(empty);list.setEmptyView(empty);
        ArrayList<Integer> ids=new ArrayList<>();
        ArrayAdapter<String> adapter=new ArrayAdapter<>(activity,android.R.layout.simple_list_item_1,new ArrayList<>());
        list.setAdapter(adapter);
        Runnable refresh=()->{
            HashSet<Integer> owned=new HashSet<>();
            for(int id:document.medalIds())owned.add(id);
            ArrayList<Integer> candidates=new ArrayList<>();
            if(add) { for(int i=0;i<names.length;i++)if(!owned.contains(i)&&!names[i].trim().isEmpty())candidates.add(i); }
            else { candidates.addAll(owned);java.util.Collections.sort(candidates); }
            String query=search.getText().toString().trim().toLowerCase(Locale.ROOT);
            ids.clear();adapter.setNotifyOnChange(false);adapter.clear();
            for(int id:candidates) {
                String[] label=id<names.length?names[id].split("\t",2):new String[0];
                String title=label.length==0?activity.getString(R.string.game_medal_row,id)
                        :activity.getString(R.string.catalog_named_id,label[0],id);
                String row=title+(label.length>1?"\n"+label[1]:"");
                if(!query.isEmpty()&&!Integer.toString(id).equals(query)&&!row.toLowerCase(Locale.ROOT).contains(query))continue;
                ids.add(id);adapter.add(row);
            }
            adapter.notifyDataSetChanged();
        };
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            public void onTextChanged(CharSequence s,int start,int before,int count) { refresh.run(); }
            public void afterTextChanged(Editable s) {}
        });
        list.setOnItemClickListener((parent,view,position,rowId)->{
            try {
                int id=ids.get(position);
                if(add)document.addMedal(id);
                else {
                    int[] owned=document.medalIds();
                    for(int i=0;i<owned.length;i++)if(owned[i]==id){document.removeMedal(i);break;}
                }
                applied.run();refresh.run();
            } catch(RuntimeException error){failed.accept(error);}
        });
        refresh.run();
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(add?R.string.medal_add_title:R.string.medal_remove_title)
                .setView(body).setNegativeButton(R.string.close,null).show();
        dialog.getWindow().setLayout(-1,(int)(activity.getResources().getDisplayMetrics().heightPixels*.8f));
    }
    private GameMedalEditor() {}
}
