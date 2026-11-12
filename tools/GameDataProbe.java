package io.github.tuxkoh.bcsfe.core;

import java.nio.file.Files;
import java.nio.file.Path;

/** Offline format probe; --repair-tail also accepts local saves without logging their contents. */
public final class GameDataProbe {
    public static void main(String[] args) throws Exception {
        if(args.length==3&&"--repair-tail".equals(args[0])){
            SaveDocument save=SaveDocument.open(Files.readAllBytes(Path.of(args[1])));
            if(!save.repairIncompleteModernTail())throw new IllegalStateException("Not a recognized incomplete trailer");
            Path output=Path.of(args[2]);
            Files.createFile(output,java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
            Files.write(output,save.toBytes());
            System.out.println("Repaired native trailer: region="+save.region().code()+", version="+save.gameVersion());
            return;
        }
        if(args.length==3&&"--items".equals(args[0])){
            byte[] source=Files.readAllBytes(Path.of(args[1]));
            Path out=Path.of(args[2]);Files.createDirectories(out);
            SaveDocument save=SaveDocument.open(source);
            Files.write(out.resolve("roundtrip.save"),save.toBytes());
            save.setLeadership(117);
            Files.write(out.resolve("leadership.save"),save.toBytes());
            for(int grade:new int[]{3,4,-1}){
                save=SaveDocument.open(source);save.setTalentOrbsAtGrade(grade,1);
                Files.write(out.resolve("orbs-"+grade+".save"),save.toBytes());
            }
            save=SaveDocument.open(source);
            for(int item=0;item<6;item++)save.setEndlessBattleDurationMinutes(item,
                    item==5?Double.POSITIVE_INFINITY:90+item);
            Files.write(out.resolve("endless.save"),save.toBytes());
            save=SaveDocument.open(source);
            save.setPasswordRefreshToken("0123456789012345678901234567890123456789");
            Files.write(out.resolve("received.save"),save.toBytes());
            save=SaveDocument.open(source);
            save.setStageMapClearTimes(SaveDocument.StageMap.BEHEMOTH,2,0,0,7);
            Files.write(out.resolve("behemoth.save"),save.toBytes());
            save=SaveDocument.open(source);save.setAkuProgress(10,5);
            Files.write(out.resolve("aku-progress.save"),save.toBytes());
            save=SaveDocument.open(source);save.clearAkuChapter(0,0,true);
            Files.write(out.resolve("aku-clear.save"),save.toBytes());
            save=SaveDocument.open(source);save.setAkuClearTimes(0,0,48,7);
            Files.write(out.resolve("aku-stage.save"),save.toBytes());
            return;
        }
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
            for(int version:new int[]{150501,150601,150700,150701,150702,150703,150704,160000}){
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
                int editedCat=Math.min(intermediate.catCount(),UpstreamGameData.forVersion(region,version).unitBuy.length)-1;
                intermediate.setCatBaseLevel(editedCat,10);
                intermediate.setCatPlusLevel(editedCat,3);
                intermediate.setCatCurrentForm(editedCat,1);
                if(intermediate.gameVersion()!=version||!intermediate.checksumValid())
                    throw new AssertionError("Intermediate edit changed version or checksum");
                Files.write(out.resolve(code+"-intermediate-"+version+"-edited.save"),intermediate.toBytes());
                for(int id=0;id<Math.min(intermediate.catCount(),UpstreamGameData.forVersion(region,version).unitBuy.length);id++){
                    if(UpstreamGameData.forVersion(region,version)!=UpstreamGameData.forRegion(region))break;
                    if(GameDataRules.totalForms(region,version,id)!=GameDataRules.totalForms(region,gv,id)
                            ||GameDataRules.catMaxPlus(region,version,id)!=GameDataRules.catMaxPlus(region,gv,id)
                            ||GameDataRules.catObtainable(region,version,id)!=GameDataRules.catObtainable(region,gv,id))
                        throw new AssertionError("Intermediate BCData selection differs: "+code+" "+version);
                }
            }
        }
    }
}
