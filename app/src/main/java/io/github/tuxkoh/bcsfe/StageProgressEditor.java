package io.github.tuxkoh.bcsfe;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import io.github.tuxkoh.bcsfe.core.SaveDocument;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** A named map browser and crown-specific stage tiles; mutations stay in SaveDocument. */
final class StageProgressEditor {
    interface NumberInput { void show(String title,int current,IntConsumer change); }
    private final AppCompatActivity activity;
    private final SaveDocument document;
    private final NumberInput number;
    private final GameCatalog catalog;
    private final Consumer<RuntimeException> failed;
    private final Runnable applied;

    StageProgressEditor(AppCompatActivity activity,SaveDocument document,NumberInput number,Runnable applied,Consumer<RuntimeException> failed) {
        this.activity=activity;this.document=document;this.number=number;this.applied=applied;this.failed=failed;
        catalog=new GameCatalog(activity,document.region());
    }

    void showMaps(SaveDocument.StageMap type,String title,int min,int max,int mapBase) {
        LinearLayout body=body();
        EditText search=new EditText(activity);search.setSingleLine(true);search.setHint(R.string.visual_map_search);
        body.addView(search);
        ListView list=new ListView(activity);body.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        TextView empty=new TextView(activity);empty.setText(R.string.visual_no_matches);body.addView(empty);list.setEmptyView(empty);
        ArrayList<Integer> ids=new ArrayList<>();
        ArrayList<String> labels=new ArrayList<>();
        for(int id=min;id<=max;id++)labels.add(catalog.mapName(type,mapBase+id,id));
        ArrayAdapter<String> adapter=new ArrayAdapter<>(activity,android.R.layout.simple_list_item_1,new ArrayList<>());
        list.setAdapter(adapter);
        Runnable filter=()->{
            String query=search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            ids.clear();adapter.setNotifyOnChange(false);adapter.clear();
            for(int id=min;id<=max;id++) {
                String label=labels.get(id-min);
                if(!query.isEmpty()&&!Integer.toString(id).equals(query)&&!label.toLowerCase(java.util.Locale.ROOT).contains(query))continue;
                ids.add(id);adapter.add(label);
            }
            adapter.notifyDataSetChanged();
        };
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            public void onTextChanged(CharSequence s,int start,int before,int count) {filter.run();}
            public void afterTextChanged(Editable s) {}
        });
        list.setOnItemClickListener((p,v,index,id)->{
            int selected=ids.get(index);
            showMap(type,mapBase+selected,catalog.mapName(type,mapBase+selected,selected));
        });
        filter.run();show(title,body);
    }

    void showMap(SaveDocument.StageMap type,int map,String title) {
        try {
            int crowns=document.stageMapStarCount(type,map);
            if(crowns==0){Toast.makeText(activity,R.string.no_map_data,Toast.LENGTH_SHORT).show();return;}
            LinearLayout body=body();
            TextView hint=new TextView(activity);hint.setText(R.string.visual_stage_hint);body.addView(hint);
            int[] crown={0};
            GridView grid=new GridView(activity);grid.setNumColumns(2);grid.setHorizontalSpacing(dp(8));grid.setVerticalSpacing(dp(8));
            TextView summary=new TextView(activity);summary.setPadding(0,dp(12),0,dp(8));
            String[] names=catalog.stageNames(type,map);
            TileAdapter tiles=new TileAdapter(names);grid.setAdapter(tiles);
            Runnable refresh=()->{
                tiles.counts=document.stageMapClearTimes(type,map,crown[0]);
                // Saves reserve extra stage slots; known BCData names delimit real stages.
                if(names.length>0&&names.length<tiles.counts.length)
                    tiles.counts=java.util.Arrays.copyOf(tiles.counts,names.length);
                updateSummary(summary,tiles.counts);tiles.notifyDataSetChanged();
            };
            LinearLayout switches=new LinearLayout(activity);
            for(int i=0;i<crowns;i++) {
                final int index=i;
                MaterialButton button=new MaterialButton(activity);button.setText(activity.getString(R.string.visual_crown_option,i+1));
                button.setContentDescription(activity.getString(R.string.crown_count_option,i+1));
                button.setMinWidth(0);button.setMinimumWidth(0);button.setPadding(0,0,0,0);
                button.setCheckable(true);button.setChecked(i==0);
                LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,dp(52),1);
                if(i>0)params.leftMargin=dp(4);
                switches.addView(button,params);
                button.setOnClickListener(v->{
                    try {
                        crown[0]=index;
                        selectCrown(switches,index);
                        refresh.run();grid.setSelection(0);
                    }catch(RuntimeException error){failed.accept(error);}
                });
            }
            selectCrown(switches,0);
            body.addView(switches);body.addView(summary);body.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
            grid.setOnItemClickListener((p,v,stage,id)->{
                final int selectedCrown=crown[0];
                changeStage(tiles,stage,value->{
                    document.setStageMapClearTimes(type,map,selectedCrown,stage,value);refresh.run();
                });
            });
            refresh.run();show(title,body);
        }catch(RuntimeException error){failed.accept(error);}
    }

    void showStory(int chapter) {
        try {
            LinearLayout body=body();
            TextView hint=new TextView(activity);hint.setText(R.string.visual_stage_hint);body.addView(hint);
            TextView summary=new TextView(activity);summary.setPadding(0,dp(12),0,dp(8));body.addView(summary);
            GridView grid=new GridView(activity);grid.setNumColumns(2);grid.setHorizontalSpacing(dp(8));grid.setVerticalSpacing(dp(8));
            body.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
            TileAdapter tiles=new TileAdapter(catalog.storyStages(chapter));grid.setAdapter(tiles);
            Runnable refresh=()->{
                tiles.counts=new int[document.storyStageCount()];
                for(int i=0;i<tiles.counts.length;i++)tiles.counts[i]=document.storyClearTimes(chapter,i);
                updateSummary(summary,tiles.counts);tiles.notifyDataSetChanged();
            };
            grid.setOnItemClickListener((p,v,stage,id)->changeStage(tiles,stage,value->{
                document.setStoryClearTimes(chapter,stage,value);refresh.run();
            }));
            refresh.run();show(activity.getResources().getStringArray(R.array.story_chapter_names)[chapter],body);
        }catch(RuntimeException error){failed.accept(error);}
    }

    private void changeStage(TileAdapter tiles,int stage,IntConsumer change) {
        try {
            if(tiles.counts[stage]==0) { change.accept(1);applied.run(); }
            else number.show(tiles.label(stage),tiles.counts[stage],change);
        }catch(RuntimeException error){failed.accept(error);}
    }

    private void selectCrown(LinearLayout switches,int selected) {
        for(int i=0;i<switches.getChildCount();i++) {
            MaterialButton button=(MaterialButton)switches.getChildAt(i);
            button.setChecked(i==selected);
            button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(activity.getColor(i==selected?R.color.primary:R.color.surface_tint)));
            button.setTextColor(activity.getColor(i==selected?R.color.surface:R.color.ink));
        }
    }

    private void updateSummary(TextView summary,int[] counts) {
        int cleared=0;for(int count:counts)if(count>0)cleared++;
        summary.setText(activity.getString(R.string.visual_stage_summary,cleared,counts.length));
    }
    private LinearLayout body() {
        LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(8),dp(20),0);return body;
    }
    private void show(String title,View body) {
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(title).setView(body).setNegativeButton(R.string.close,null).show();
        dialog.getWindow().setLayout(-1,(int)(activity.getResources().getDisplayMetrics().heightPixels*.8f));
    }
    private int dp(int n){return (int)(n*activity.getResources().getDisplayMetrics().density);}

    private final class TileAdapter extends BaseAdapter {
        private final String[] names;
        private int[] counts=new int[0];
        TileAdapter(String[] names){this.names=names;}
        String label(int stage){return stage<names.length&&!names[stage].isEmpty()?activity.getString(R.string.catalog_named_id,names[stage],stage):activity.getString(R.string.visual_stage_id,stage);}
        public int getCount(){return counts.length;}
        public Object getItem(int position){return counts[position];}
        public long getItemId(int position){return position;}
        public View getView(int position,View recycled,ViewGroup parent){
            TextView tile=recycled instanceof TextView?(TextView)recycled:new TextView(activity);
            tile.setText(activity.getString(R.string.visual_stage_tile,label(position),activity.getString(counts[position]>0?R.string.state_cleared:R.string.state_uncleared),counts[position]));
            tile.setTextColor(activity.getColor(R.color.ink));tile.setTextSize(14);tile.setTypeface(Typeface.DEFAULT);
            tile.setGravity(Gravity.CENTER);tile.setPadding(dp(8),dp(12),dp(8),dp(12));tile.setMinHeight(dp(116));
            GradientDrawable background=new GradientDrawable();background.setCornerRadius(dp(12));
            background.setColor(activity.getColor(counts[position]>0?R.color.surface_tint:R.color.surface));
            background.setStroke(dp(counts[position]>0?2:1),activity.getColor(counts[position]>0?R.color.primary:R.color.divider));tile.setBackground(background);
            return tile;
        }
    }
}
