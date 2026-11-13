package io.github.tuxkoh.bcsfe;

import android.content.Context;
import io.github.tuxkoh.bcsfe.core.SaveDocument;

/** Display names follow the save's game region, independently of the app language. */
final class GameCatalog {
    private final Context context;
    private final String region;
    private final java.util.Map<String,String[]> cache=new java.util.HashMap<>();
    GameCatalog(Context context, SaveDocument.Region region) { this.context=context; this.region=region.code(); }

    String[] storyStages(int chapter) { return array("story_stage_names_"+region+"_"+(chapter/3)); }

    String[] medals() { return array("medal_names_"+region); }

    String mapName(SaveDocument.StageMap type, int map, int displayId) {
        String name = at(array("map_names_"+region+"_"+code(type,map)),localMap(type,map));
        return name.isEmpty() ? context.getString(R.string.visual_map_id,displayId)
                : context.getString(R.string.catalog_named_id,name,displayId);
    }

    String[] stageNames(SaveDocument.StageMap type,int map) {
        String row=at(array("stage_names_"+region+"_"+code(type,map)),localMap(type,map));
        return row.isEmpty()?new String[0]:row.split("\t",-1);
    }

    private static int localMap(SaveDocument.StageMap type,int map) {
        return type==SaveDocument.StageMap.EVENT?map%500:map;
    }

    private static String code(SaveDocument.StageMap type,int map) {
        return switch(type) {
            case EVENT -> switch(map/500) { case 0 -> "rn"; case 1 -> "rs"; case 2 -> "rc"; default -> ""; };
            case UNCANNY -> "rna"; case CATAMIN -> "rb"; case TOWER -> "rv";
            case GAUNTLETS -> "ra"; case ENIGMA_CLEARS -> "rh"; case COLLAB_GAUNTLETS -> "rca";
            case ZERO_LEGENDS -> "rnd"; case LEGEND_QUEST -> "rd"; case BEHEMOTH -> "rq";
            case DOJO -> "rsr"; default -> "";
        };
    }

    private String[] array(String name) {
        return cache.computeIfAbsent(name,key->{
            int id=context.getResources().getIdentifier(key,"array",context.getPackageName());
            return id==0?new String[0]:context.getResources().getStringArray(id);
        });
    }
    private static String at(String[] values,int index) { return index>=0&&index<values.length?values[index]:""; }
}
