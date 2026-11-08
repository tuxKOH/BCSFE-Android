package io.github.tuxkoh.bcsfe.core;

import java.nio.file.Files;
import java.nio.file.Path;

/** Offline integration probe; inputs are bundled scrubbed templates only. */
public final class GameDataProbe {
    public static void main(String[] args) throws Exception {
        if(args.length==3&&"--tickets".equals(args[0])){
            byte[] source=Files.readAllBytes(Path.of(args[1]));
            SaveDocument save=SaveDocument.open(source);
            if(save.normalTickets()!=111||save.rareTickets()!=22)
                throw new AssertionError("Variable-prefix ticket values differ from upstream: normal="
                        +save.normalTickets()+", rare="+save.rareTickets());
            if(!java.util.Arrays.equals(source,save.toBytes()))
                throw new AssertionError("Opening a variable-prefix save changed its bytes");
            if(save.platinumTickets()!=3||save.legendTickets()!=4||save.platinumShards()!=5)
                throw new AssertionError("Other ticket values differ from upstream: platinum="+save.platinumTickets()
                        +", legend="+save.legendTickets()+", shards="+save.platinumShards());
            save.setNormalTickets(123);save.setRareTickets(234);
            save.setPlatinumTickets(6);save.setLegendTickets(2);save.setPlatinumShards(8);
            Files.write(Path.of(args[2]),save.toBytes());
            return;
        }
        Path templates=Path.of(args[0]), out=Path.of(args[1]);
        Files.createDirectories(out);
        for(SaveDocument.Region region:SaveDocument.Region.values()){
            String code=region.code();
            SaveDocument save=SaveDocument.fromNewSaveTemplate(Files.readAllBytes(templates.resolve(code+".save")));
            byte[] source=save.toBytes();
            Files.write(out.resolve(code+"-new.save"),source);
            for(int target:GameVersions.conversionTargets(region)){
                SaveDocument converted=SaveDocument.open(source);
                converted.convertGameVersion(target);
                Files.write(out.resolve(code+"-version-"+target+".save"),converted.toBytes());
            }
            SaveDocument tickets=SaveDocument.open(source);
            tickets.setNormalTickets(123);tickets.setRareTickets(234);
            Files.write(out.resolve(code+"-tickets.save"),tickets.toBytes());
            SaveDocument cat=SaveDocument.open(source);
            cat.setCatBaseLevel(cat.catCount()-1,10);
            cat.setCatPlusLevel(cat.catCount()-1,3);
            cat.setCatCurrentForm(cat.catCount()-1,1);
            Files.write(out.resolve(code+"-cat.save"),cat.toBytes());
            SaveDocument talents=SaveDocument.open(source);
            talents.maxAllCatTalents();
            Files.write(out.resolve(code+"-talents.save"),talents.toBytes());
            StringBuilder rules=new StringBuilder();
            int gv=save.gameVersion();
            for(int id=0;id<save.catCount();id++){
                rules.append("cat,").append(id).append(',')
                        .append(GameDataRules.catRarity(region,gv,id)).append(',')
                        .append(GameDataRules.catOriginalMaxBase(region,gv,id)).append(',')
                        .append(GameDataRules.catMaxNoCatseye(region,gv,id)).append(',')
                        .append(GameDataRules.catMaxCatseye(region,gv,id)).append(',')
                        .append(GameDataRules.catMaxPlus(region,gv,id)).append(',')
                        .append(GameDataRules.catUnknown22(region,gv,id)).append(',')
                        .append(GameDataRules.totalForms(region,gv,id)).append(',')
                        .append(GameDataRules.catObtainable(region,gv,id)?1:0).append(',')
                        .append(GameDataRules.catRankLimitBase(region,gv,id,x->true)).append(',')
                        .append(GameDataRules.catRankLimitPlus(region,gv,id,x->true)).append('\n');
            }
            UpstreamGameData.Snapshot snapshot=UpstreamGameData.forRegion(region);
            for(int[] skill:snapshot.talents)rules.append("talent,").append(skill[0]).append(',').append(skill[1]).append(',').append(GameDataRules.talentMaxLevel(region,gv,skill[0],skill[1])).append('\n');
            for(int[] mission:snapshot.missions)rules.append("mission,").append(mission[0]).append(',').append(GameDataRules.missionTarget(region,gv,mission[0])).append('\n');
            for(int[] map:snapshot.maps)rules.append("map,").append(map[0]).append(',').append(GameDataRules.mapCrowns(region,gv,map[0])).append('\n');
            for(int[] scheme:snapshot.schemes)if(!GameDataRules.validSchemeItem(region,gv,scheme[0]))throw new AssertionError("Missing scheme");
            for(int i=0;i<GameDataRules.dropPairCount(region,gv);i++)rules.append("drop,").append(GameDataRules.dropCat(region,gv,i)).append(',').append(GameDataRules.dropSlot(region,gv,i)).append('\n');
            for(int i=0;i<GameDataRules.rankGiftCount(region,gv);i++)rules.append("gift,").append(i).append(',').append(GameDataRules.rankGiftThreshold(region,gv,i)).append('\n');
            Files.writeString(out.resolve(code+"-rules.csv"),rules);
            for(int version:new int[]{150501,150601,150700,150701,150702,150703}){
                if(!GameVersions.supported(region,version)||version==gv)continue;
                SaveDocument intermediate=SaveDocument.open(source);
                intermediate.convertGameVersion(version);
                byte[] imported=intermediate.toBytes();
                intermediate=SaveDocument.open(imported);
                if(intermediate.needsUnsupportedImportWarning()||!intermediate.hasItemProfile()
                        ||!java.util.Arrays.equals(imported,intermediate.toBytes()))
                    throw new AssertionError("Intermediate import failed: "+code+" "+version);
                Files.write(out.resolve(code+"-intermediate-"+version+".save"),imported);
                intermediate.setNormalTickets(123);intermediate.setRareTickets(234);
                intermediate.setCatBaseLevel(intermediate.catCount()-1,10);
                intermediate.setCatPlusLevel(intermediate.catCount()-1,3);
                intermediate.setCatCurrentForm(intermediate.catCount()-1,1);
                if(intermediate.gameVersion()!=version||!intermediate.checksumValid())
                    throw new AssertionError("Intermediate edit changed version or checksum");
                Files.write(out.resolve(code+"-intermediate-"+version+"-edited.save"),intermediate.toBytes());
                for(int id=0;id<intermediate.catCount();id++){
                    if(GameDataRules.totalForms(region,version,id)!=GameDataRules.totalForms(region,gv,id)
                            ||GameDataRules.catMaxPlus(region,version,id)!=GameDataRules.catMaxPlus(region,gv,id)
                            ||GameDataRules.catObtainable(region,version,id)!=GameDataRules.catObtainable(region,gv,id))
                        throw new AssertionError("Intermediate BCData selection differs: "+code+" "+version);
                }
            }
        }
    }
}
