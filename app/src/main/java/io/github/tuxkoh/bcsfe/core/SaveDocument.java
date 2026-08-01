package io.github.tuxkoh.bcsfe.core;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

/** A lossless save buffer with the common fields used by the first editor batch. */
public final class SaveDocument {
    private static final String EVENT_MAP_CROWNS = "1111111134444411111111111141144443334141111111111111111111113333341111313111111313111111111111111111111111111111111311111111111314111111111111111131111111111111111111111111111111333311111111111111111111111141111111111111111111111111111111111111111111111111111143111113111111111111111111311111111114411112111133111111111143131311111111111111111211111111111111111111111111111111111121311122121111111112221111133111112111111111111111121111";
    private static final String COLLAB_MAP_CROWNS = "144312144221133313111443314111133331111113311311133143333311111114443333333331314131144244114133333341333331111111111111111111333133131111111111111111111111111111134431343114433311111331441111114444433331143311111133422144414224331111111111111111111111111111111114221111212";
    public enum StageMap { CHALLENGE, GAUNTLETS, ENIGMA_CLEARS, COLLAB_GAUNTLETS, EVENT, UNCANNY, CATAMIN, BEHEMOTH, LEGEND_QUEST, TOWER, ZERO_LEGENDS, DOJO }
    public enum GamblingTable { WILDCAT_SLOTS, CAT_SCRATCHER }
    public enum Region {
        EN("en", "battlecatsen"), JP("jp", "battlecats"), TW("tw", "battlecatstw"), KR("kr", "battlecatskr");
        private final String code;
        private final String salt;
        Region(String code, String salt) { this.code = code; this.salt = salt; }
        public String code() { return code; }
        String salt() { return salt; }
    }

    private byte[] bytes;
    private Region region;
    private final int integerStart;
    private int cannonBaseCache = -1;
    private int goldPassBaseCache = -1;
    private int talentTableBaseCache = -1;
    private int storageTableBaseCache = -1;
    private int enigmaBaseCache = -1;
    private int eventTableBaseCache = -1;

    private SaveDocument(byte[] source, Region region, int integerStart) {
        this.bytes = source;
        this.region = region;
        this.integerStart = integerStart;
    }

    public static SaveDocument open(byte[] source) {
        if (source == null || source.length < 48) throw new IllegalArgumentException("Save is too small");
        for (Region candidate : Region.values()) {
            int start = Offsets.offsets_122;
            if (hasValidHash(source, candidate, start)) return new SaveDocument(source.clone(), candidate, start);
        }
        throw new IllegalArgumentException("Invalid save checksum or unsupported region");
    }

    public static SaveDocument open(byte[] source, Region region) {
        if (source == null || source.length < 48) throw new IllegalArgumentException("Save is too small");
        int start = Offsets.offsets_122;
        if (!hasValidHash(source, region, start)) throw new IllegalArgumentException("Invalid save checksum");
        return new SaveDocument(source.clone(), region, start);
    }

    public byte[] toBytes() { return bytes.clone(); }
    public Region region() { return region; }
    public void convertRegion(Region target) {
        if (target == null) throw new IllegalArgumentException("Missing region");
        if (target == region) return;
        if ((target == Region.JP || region == Region.JP) && gameVersion() != 150500) {
            throw new UnsupportedOperationException("JP conversion is supported for 15.5 saves");
        }

        if (region == Region.JP) transformJpToInternational();
        if (region == Region.EN) removeEnRegionBlock();

        if (target == Region.JP) {
            transformInternationalToJp();
        } else {
            region = target;
            if (target == Region.EN) insertEnRegionBlock();
        }
        refreshHash();
    }

    private void removeEnRegionBlock() {
        if (gameVersion() >= 100600 && bytes.length > Offsets.offsets_143) {
            int offset = afterCannonsWithoutRegion(Offsets.offsets_1);
            splice(offset, 5, 0);
        }
        region = Region.TW;
    }

    private void insertEnRegionBlock() {
        if (gameVersion() >= 100600 && bytes.length > Offsets.offsets_143) {
            int offset = afterCannonsWithoutRegion(Offsets.offsets_1);
            splice(offset, 0, 5);
            putInt(offset, 100600);
            bytes[offset + 4] = 0;
        }
    }

    private void transformInternationalToJp() {
        if (region == Region.EN) throw new IllegalStateException("EN block must be normalized first");

        int marker120700 = findIntNear(120700, lateOffset(Offsets.offsets_2), 16);
        putInt(marker120700, 130000);
        int marker130600 = findIntNear(130600, lateOffset(Offsets.offsets_3), 16);
        byte[] movedStamp = Arrays.copyOfRange(bytes, fixed(Offsets.offsets_4), fixed(Offsets.offsets_4) + 8);
        byte[] movedShort = Arrays.copyOfRange(bytes, marker130600 - 2, marker130600);
        byte energyNotification = bytes[fixed(Offsets.offsets_5)];

        // Apply from the end of the file so every coordinate remains a source coordinate.
        splice(marker130600 + 4, 0, 2);
        System.arraycopy(movedShort, 0, bytes, marker130600 + 4, 2);
        splice(marker130600 - 2, 2, 0);
        splice(lateOffset(Offsets.offsets_6), 1, 0);
        splice(fixed(Offsets.offsets_7), 0, 1);
        bytes[fixed(Offsets.offsets_7)] = energyNotification;
        splice(fixed(Offsets.offsets_8), 1, 0);
        splice(fixed(Offsets.offsets_9), 0, 8);
        System.arraycopy(movedStamp, 0, bytes, fixed(Offsets.offsets_9), 8);
        splice(fixed(Offsets.offsets_4), 8, 4);
        Arrays.fill(bytes, fixed(Offsets.offsets_4), fixed(Offsets.offsets_4) + 4, (byte) 0);
        splice(fixed(Offsets.offsets_10), 33, 0);
        splice(fixed(Offsets.offsets_11), 4, 0);
        splice(fixed(Offsets.offsets_12), 1, 0);
        splice(fixed(Offsets.offsets_13), 1, 0);
        splice(fixed(Offsets.offsets_14), 1, 0);
        splice(Offsets.offsets_22, 1, 0);
        region = Region.JP;
    }

    private void transformJpToInternational() {
        int marker130000 = findIntNear(130000, lateOffset(Offsets.offsets_2), 16);
        putInt(marker130000, 120700);
        int marker130600 = findIntNear(130600, lateOffset(Offsets.offsets_3), 16);
        byte[] movedShort = Arrays.copyOfRange(bytes, marker130600 + 4, marker130600 + 6);
        int movedStampOffset = fixed(Offsets.offsets_9) - 8;
        byte[] movedStamp = Arrays.copyOfRange(bytes, movedStampOffset, movedStampOffset + 8);
        byte energyNotification = bytes[fixed(Offsets.offsets_7) - 1];
        byte[] internationalBlock = new byte[33];
        internationalBlock[26] = energyNotification;

        splice(marker130600 + 4, 2, 0);
        splice(marker130600, 0, 2);
        System.arraycopy(movedShort, 0, bytes, marker130600, 2);
        splice(lateOffset(Offsets.offsets_15), 0, 1);
        bytes[lateOffset(Offsets.offsets_15)] = 0;
        splice(fixed(Offsets.offsets_7) - 1, 1, 0);
        splice(fixed(Offsets.offsets_16), 0, 1);
        bytes[fixed(Offsets.offsets_16)] = 0;
        splice(movedStampOffset, 8, 0);
        splice(fixed(Offsets.offsets_4), 4, 8);
        System.arraycopy(movedStamp, 0, bytes, fixed(Offsets.offsets_4), 8);
        splice(fixed(Offsets.offsets_17), 0, internationalBlock.length);
        System.arraycopy(internationalBlock, 0, bytes, fixed(Offsets.offsets_17), internationalBlock.length);
        splice(fixed(Offsets.offsets_18), 0, 4);
        Arrays.fill(bytes, fixed(Offsets.offsets_18), fixed(Offsets.offsets_18) + 4, (byte) 0);
        splice(fixed(Offsets.offsets_19), 0, 1);
        bytes[fixed(Offsets.offsets_19)] = 0;
        splice(fixed(Offsets.offsets_20), 0, 1);
        bytes[fixed(Offsets.offsets_20)] = 0;
        splice(fixed(Offsets.offsets_21), 0, 1);
        bytes[fixed(Offsets.offsets_21)] = 0;
        splice(Offsets.offsets_22, 0, 1);
        bytes[Offsets.offsets_22] = 0;
        region = Region.TW;
    }
    public int gameVersion() { return intAt(Offsets.offsets_23); }
    public void convertGameVersion(int target) {
        int source=gameVersion();
        if(source==target)return;
        if(source>=140300&&source<=150500&&target==140000){
            if(source>=140500)convert140500EmbeddedLayout(source,140000);
            if(source>=140100){int marker90500=findInt(90500);splice(marker90500-2,2,0);}
            int start=findInt(140000)+4,end=findInt(140300)+4;splice(start,end-start,0);
            putInt(Offsets.offsets_23,target);refreshHash();return;
        }
        if(source==140000&&target>=140300&&target<=150500){
            if(target>=140500)convert140500EmbeddedLayout(source,target);
            int marker90500=findInt(90500);splice(marker90500,0,2);putShort(marker90500,0);
            int marker140000=findInt(140000),extra=target>=150500?6:target>=150300?5:0;
            byte[] tail=new byte[(target<140500?5:0)+10+14+extra];int p=0;
            if(target<140500){tail[p++]=0;writeInt(tail,p,140100);p+=4;}
            tail[p++]=0;tail[p++]=0;p+=4;writeInt(tail,p,140200);p+=4;
            tail[p++]=0;p+=extra;tail[p++]=0;tail[p++]=0;p+=4;p+=2;tail[p++]=0;writeInt(tail,p,140300);
            splice(marker140000+4,0,tail.length);System.arraycopy(tail,0,bytes,marker140000+4,tail.length);
            putInt(Offsets.offsets_23,target);refreshHash();return;
        }
        if(source<140300||source>150500||target<140300||target>150500)throw new UnsupportedOperationException("Version conversion is supported from 14.3 through 15.5, plus downgrade to 14.0");
        convert140500EmbeddedLayout(source,target);
        convert140500RecordLayout(source,target);
        int marker=findIntNear(140200,bytes.length-406,96);
        int listCount=byteAt(marker+4),fields=marker+5+listCount;
        int sourceExtra=source>=150500?6:source>=150300?5:0;
        int targetExtra=target>=150500?6:target>=150300?5:0;
        if(targetExtra<sourceExtra)splice(fields+targetExtra,sourceExtra-targetExtra,0);
        else if(targetExtra>sourceExtra){splice(fields+sourceExtra,0,targetExtra-sourceExtra);Arrays.fill(bytes,fields+sourceExtra,fields+targetExtra,(byte)0);}
        putInt(Offsets.offsets_23,target);
        refreshHash();
    }
    private void convert140500EmbeddedLayout(int source,int target) {
        boolean sourceLegacy=source<140500,targetLegacy=target<140500;
        if(sourceLegacy==targetLegacy)return;
        if(sourceLegacy){
            int dojoString=afterCannons(Offsets.offsets_24);
            splice(dojoString,0,4);putInt(dojoString,0);
            int enigmaExtra=enigmaBaseOffset()+12+byteAt(enigmaBaseOffset()+11)*17;
            splice(enigmaExtra,0,1);
        }else{
            int enigmaExtra=enigmaBaseOffset()+12+byteAt(enigmaBaseOffset()+11)*17;
            splice(enigmaExtra,byteAt(enigmaExtra)==0?1:18,0);
            int dojoString=afterCannons(Offsets.offsets_24),length=intAt(dojoString);
            if(length<0||length>1024)throw new IllegalStateException("Invalid Dojo ranking string");
            splice(dojoString,4+length,0);
        }
    }
    private void convert140500RecordLayout(int source,int target) {
        boolean sourceLegacy=source<140500,targetLegacy=target<140500;
        if(sourceLegacy==targetLegacy)return;
        int marker=findInt(140000),countOffset=marker+(sourceLegacy?9:4),count=byteAt(countOffset),offset=countOffset+1;
        if(count>100)throw new IllegalStateException("Invalid 14.2 records");
        int[] stringOffsets=new int[count],stringLengths=new int[count];
        for(int i=0;i<count;i++){
            int stringOffset=offset+27;stringOffsets[i]=stringOffset;
            if(sourceLegacy){stringLengths[i]=0;offset+=28;}
            else{int length=intAt(stringOffset);if(length<0||length>1024)throw new IllegalStateException("Invalid 14.2 record string");stringLengths[i]=4+length;offset+=32+length;}
        }
        for(int i=count-1;i>=0;i--)if(sourceLegacy){splice(stringOffsets[i],0,4);putInt(stringOffsets[i],0);}else splice(stringOffsets[i],stringLengths[i],0);
        if(sourceLegacy)splice(marker+4,5,0);
        else{splice(marker+4,0,5);bytes[marker+4]=0;putInt(marker+5,140100);}
    }
    public boolean muteBgm() { return byteAt(integerStart) != 0; }
    public boolean muteSe() { return byteAt(integerStart + Offsets.offsets_123) != 0; }

    // The upstream format stores the two booleans before these 32-bit values.
    public int catFood() { return intAt(integerStart + Offsets.offsets_124); }
    public int currentEnergy() { return intAt(integerStart + Offsets.offsets_125); }
    public int xp() { return intAt(integerStart + (region == Region.JP ? Offsets.offsets_126 : Offsets.offsets_127)); }
    public int tutorialState() { return intAt(integerStart + (region == Region.JP ? Offsets.offsets_128 : Offsets.offsets_129)); }
    public int normalTickets() { return profiledInt(ProfileField.NORMAL_TICKETS); }
    public int rareTickets() { return profiledInt(ProfileField.RARE_TICKETS); }
    public int platinumTickets() { return profiledInt(ProfileField.PLATINUM_TICKETS); }
    public int legendTickets() { return profiledInt(ProfileField.LEGEND_TICKETS); }
    public int platinumShards() { return profiledInt(ProfileField.PLATINUM_SHARDS); }
    public int np() { return profiledInt(ProfileField.NP); }
    public int leadership() { ensureItemProfile();return ushortAt(findInt(80200)-6); }

    public void setMuteBgm(boolean value) { bytes[integerStart] = (byte) (value ? 1 : 0); refreshHash(); }
    public void setMuteSe(boolean value) { bytes[integerStart + Offsets.offsets_123] = (byte) (value ? 1 : 0); refreshHash(); }
    public void setCatFood(int value) { checkAmount("Cat Food",value,45000);putInt(integerStart + Offsets.offsets_124, value); refreshHash(); }
    public void setCurrentEnergy(int value) { putInt(integerStart + Offsets.offsets_125, value); refreshHash(); }
    public void setXp(int value) { checkAmount("XP",value,99999999);putInt(integerStart + (region == Region.JP ? Offsets.offsets_126 : Offsets.offsets_127), value); refreshHash(); }
    public void setTutorialState(int value) { putInt(integerStart + (region == Region.JP ? Offsets.offsets_128 : Offsets.offsets_129), value); refreshHash(); }
    public void clearTutorial() { ensureItemProfile();putInt(fixed(Offsets.offsets_25),Math.max(1,intAt(fixed(Offsets.offsets_25))));putInt(fixed(Offsets.offsets_26),Math.max(2,intAt(fixed(Offsets.offsets_26))));putInt(fixed(Offsets.offsets_27),Math.max(1,intAt(fixed(Offsets.offsets_27))));putInt(fixed(Offsets.offsets_28),Math.max(1,intAt(fixed(Offsets.offsets_28))));putInt(fixed(Offsets.offsets_29),Math.max(2,intAt(fixed(Offsets.offsets_29))));putInt(fixed(Offsets.offsets_30),Math.max(2,intAt(fixed(Offsets.offsets_30))));refreshHash(); }
    public void setNormalTickets(int value) { checkAmount("Normal Tickets",value,2999);setProfiledInt(ProfileField.NORMAL_TICKETS, value); }
    public void setRareTickets(int value) { checkAmount("Rare Tickets",value,299);setProfiledInt(ProfileField.RARE_TICKETS, value); }
    public void setPlatinumTickets(int value) { checkAmount("Platinum Tickets",value,9);setProfiledInt(ProfileField.PLATINUM_TICKETS, value); }
    public void setLegendTickets(int value) { checkAmount("Legend Tickets",value,4);setProfiledInt(ProfileField.LEGEND_TICKETS, value); }
    public void setPlatinumShards(int value) { checkAmount("Platinum Shards",value,Math.max(0,(9-platinumTickets())*10+9));setProfiledInt(ProfileField.PLATINUM_SHARDS, value); }
    public void setNp(int value) { checkAmount("NP",value,9999);setProfiledInt(ProfileField.NP, value); }
    public void setLeadership(int value) { ensureItemProfile();checkAmount("Leadership",value,9999);putShort(findInt(80200)-6,value);refreshHash(); }
    public boolean hasItemProfile() { return profileOffset(ProfileField.NORMAL_TICKETS) >= 0; }
    public int[] battleItems() { return intArray(fixed(Offsets.offsets_31), 6); }
    public int[] catseyes() { return intArray(fixed(Offsets.offsets_32), 6); }
    public int[] catamins() { return intArray(fixed(Offsets.offsets_33), 3); }
    public int[] catfruit() { return intArray(fixed(Offsets.offsets_34), 29); }
    public int[] treasureChests() { return intArray(treasureChestOffset(), 39); }
    public int[] labyrinthMedals() { ensureItemProfile(); int marker=findInt(111000),count=byteAt(marker-9);if(count!=4)throw new IllegalStateException("Invalid labyrinth medals");return new int[]{ushortAt(marker-8),ushortAt(marker-6),ushortAt(marker-4),ushortAt(marker-2)}; }
    public int hundredMillionTicket() { ensureItemProfile(); return intAt(findInt(140200)-4); }
    public int goldenCpuCount() { ensureItemProfile(); return byteAt(lateOffset(Offsets.offsets_35)); }
    public void setBattleItem(int index, int value) { checkAmount("Battle Item",value,9999);setArrayInt(fixed(Offsets.offsets_31), 6, index, value); }
    public void setCatseye(int index, int value) { checkAmount("Catseye",value,9999);setArrayInt(fixed(Offsets.offsets_32), 6, index, value); }
    public void setCatamin(int index, int value) { checkAmount("Catamin",value,9999);setArrayInt(fixed(Offsets.offsets_33), 3, index, value); }
    public void setCatfruit(int index, int value) { checkAmount("Catfruit",value,gameVersion()<110400?128:998);setArrayInt(fixed(Offsets.offsets_34), 29, index, value); }
    public void setTreasureChest(int index, int value) { checkAmount("Treasure Chest",value,9999);setArrayInt(treasureChestOffset(), 39, index, value); }
    public void setLabyrinthMedal(int index, int value) { ensureItemProfile();if(index<0||index>=4)throw new IndexOutOfBoundsException();checkAmount("Labyrinth Medal",value,9999);putShort(findInt(111000)-8+index*2,value);refreshHash(); }
    public void setHundredMillionTicket(int value) { ensureItemProfile();checkAmount("100 Million Download Tickets",value,9999);putInt(findInt(140200)-4,value);refreshHash(); }
    public void resetGoldenCpuCount() { ensureItemProfile(); bytes[lateOffset(Offsets.offsets_35)]=0; refreshHash(); }
    public int filibusterStageId() { ensureItemProfile();return byteAt(afterCannons(Offsets.offsets_36)); }
    public boolean filibusterStageEnabled() { ensureItemProfile();return byteAt(afterCannons(Offsets.offsets_37))!=0; }
    public void enableFilibusterStage(int stageId) { ensureItemProfile();if(stageId<0||stageId>47)throw new IllegalArgumentException("Invalid Filibuster stage");bytes[afterCannons(Offsets.offsets_36)]=(byte)stageId;bytes[afterCannons(Offsets.offsets_37)]=1;refreshHash(); }
    public boolean hasCatProfile() { return hasItemProfile(); }
    public String inquiryCode() { ensureItemProfile(); return stringAt(inquiryCodeOffset()); }
    public String passwordRefreshToken() { ensureItemProfile(); return stringAt(passwordRefreshTokenOffset()); }
    public void setInquiryCode(String value) { ensureItemProfile();putFixedString(inquiryCodeOffset(),value);refreshHash(); }
    public void setPasswordRefreshToken(String value) { ensureItemProfile();putFixedString(passwordRefreshTokenOffset(),value);refreshHash(); }
    public long accountCreatedAt() { ensureItemProfile(); return longAt(accountCreatedAtOffset()); }
    public void setAccountCreatedAt(long value) { ensureItemProfile();putLong(accountCreatedAtOffset(),Double.doubleToRawLongBits((double)value));refreshHash(); }
    public int playTime() { ensureItemProfile(); return intAt(inquiryCodeOffset()+13); }
    public void setPlayTime(int value) { ensureItemProfile(); putInt(inquiryCodeOffset()+13,value); refreshHash(); }
    public int playTimeHours() { return Math.max(0,playTime())/108000; }
    public int playTimeMinutesPart() { return Math.max(0,playTime())/1800%60; }
    public int playTimeSecondsPart() { return Math.max(0,playTime())/30%60; }
    public void setPlayTimeComponents(int hours,int minutes,int seconds) { if(hours<0||minutes<0||seconds<0)throw new IllegalArgumentException("Invalid playtime");long frames=((long)hours*3600+(long)minutes*60+seconds)*30;setPlayTime((int)Math.min(Integer.MAX_VALUE,frames)); }
    public int userRank() { ensureCatProfile(); int rank=0; for(int i=0;i<873;i++)if(intAt(fixed(Offsets.offsets_38)+i*4)!=0)rank+=ushortAt(fixed(Offsets.offsets_39)+i*4)+1+ushortAt(fixed(Offsets.offsets_40)+i*4); for(int i=0;i<11;i++)if(i!=1)rank+=ushortAt(fixed(Offsets.offsets_41)+i*4)+1+ushortAt(fixed(Offsets.offsets_42)+i*4); return rank; }
    public int rankUpSaleValue() { ensureItemProfile(); return intAt(fixed(Offsets.offsets_43)); }
    public boolean showBanMessage() { ensureItemProfile(); return byteAt(fixed(Offsets.offsets_44))!=0; }
    public void setShowBanMessage(boolean value) { ensureItemProfile();bytes[fixed(Offsets.offsets_44)]=(byte)(value?1:0);refreshHash(); }
    public long rareSeed() { ensureItemProfile();return Integer.toUnsignedLong(intAt(fixed(Offsets.offsets_45))); }
    public long normalSeed() { ensureItemProfile();return Integer.toUnsignedLong(intAt(fixed(Offsets.offsets_46))); }
    public long eventSeed() { ensureItemProfile();return Integer.toUnsignedLong(intAt(fixed(Offsets.offsets_47))); }
    public int gamatotoXp() { ensureItemProfile(); return intAt(fixed(Offsets.offsets_48)); }
    public int challengeScore() { ensureItemProfile(); return intAt(afterCannons(Offsets.offsets_49)); }
    public void setRareSeed(long value) { ensureItemProfile();checkUnsignedInt(value);putInt(fixed(Offsets.offsets_45),(int)value);refreshHash(); }
    public void setNormalSeed(long value) { ensureItemProfile();checkUnsignedInt(value);putInt(fixed(Offsets.offsets_46),(int)value);refreshHash(); }
    public void setEventSeed(long value) { ensureItemProfile();checkUnsignedInt(value);putInt(fixed(Offsets.offsets_47),(int)value);refreshHash(); }
    public void setGamatotoXp(int value) { ensureItemProfile();if(value<0)throw new IllegalArgumentException("Invalid Gamatoto XP");putInt(fixed(Offsets.offsets_48),value);refreshHash(); }
    public void setChallengeScore(int value) { ensureItemProfile();bytes[afterCannons(Offsets.offsets_50)]=1;putInt(afterCannons(Offsets.offsets_51),3);putInt(afterCannons(Offsets.offsets_49),value);bytes[afterCannons(Offsets.offsets_52)]=1;refreshHash(); }
    public void fixGamatotoCrash() { ensureItemProfile(); putInt(fixed(Offsets.offsets_53),2); refreshHash(); }
    public void unlockEquipMenu() { ensureItemProfile(); putInt(fixed(Offsets.offsets_54),Math.max(1,intAt(fixed(Offsets.offsets_54)))); refreshHash(); }
    public int catCount() { ensureCatProfile(); return 873; }
    public int catBaseLevel(int index) { checkCat(index); return ushortAt(fixed(Offsets.offsets_39)+index*4)+1; }
    public int catPlusLevel(int index) { checkCat(index); return ushortAt(fixed(Offsets.offsets_40)+index*4); }
    public boolean catUnlocked(int index) { checkCat(index); return intAt(fixed(Offsets.offsets_38)+index*4)!=0; }
    public int catCurrentForm(int index) { checkCat(index); return intAt(fixed(Offsets.offsets_55)+index*4); }
    public int catUnlockedForms(int index) { checkCat(index); return byteAt(Offsets.offsets_133+index*4); }
    public int catFourthForm(int index) { checkCat(index); return intAt(fixed(Offsets.offsets_56)+index*4); }
    public boolean catGuideCollected(int index) { checkCat(index); return byteAt(fixed(Offsets.offsets_57)+index)!=0; }
    public void setCatBaseLevel(int index,int value) { checkCat(index);if(value<1||value>GameDataRules.catMaxBase(index))throw new IllegalArgumentException("Invalid cat base level");syncCatRankLimits(index);unlockCatRaw(index);putShort(fixed(Offsets.offsets_39)+index*4,value-1);touchRankUpSale();refreshHash(); }
    public void setCatPlusLevel(int index,int value) { checkCat(index);if(value<0||value>GameDataRules.catMaxPlus(index))throw new IllegalArgumentException("Invalid cat plus level");syncCatRankLimits(index);unlockCatRaw(index);putShort(fixed(Offsets.offsets_40)+index*4,value);touchRankUpSale();refreshHash(); }
    public void setCatUnlocked(int index,boolean value) { checkCat(index);if(value)unlockCatRaw(index);else putInt(fixed(Offsets.offsets_38)+index*4,0);touchRankUpSale();refreshHash(); }
    public void setCatCurrentForm(int index,int value) { checkCat(index); if(value<0||value>3)throw new IllegalArgumentException("Invalid form");unlockCatRaw(index);putInt(fixed(Offsets.offsets_55)+index*4,value);touchRankUpSale();refreshHash(); }
    public void setCatUnlockedForms(int index,int value) { checkCat(index);if(value<0||value>3)throw new IllegalArgumentException("Invalid unlocked forms");unlockCatRaw(index);putFormValue(index,value);touchRankUpSale();refreshHash(); }
    public void setCatFourthForm(int index,int value) { checkCat(index);if(value<0||value>2)throw new IllegalArgumentException("Invalid fourth form");unlockCatRaw(index);putInt(fixed(Offsets.offsets_56)+index*4,value);touchRankUpSale();refreshHash(); }
    public void resetCat(int index) { checkCat(index);putInt(fixed(Offsets.offsets_38)+index*4,0);putShort(fixed(Offsets.offsets_40)+index*4,0);putShort(fixed(Offsets.offsets_39)+index*4,0);putInt(fixed(Offsets.offsets_55)+index*4,0);putInt(fixed(Offsets.offsets_58)+index*4,0);putFormValue(index,0);bytes[fixed(Offsets.offsets_57)+index]=0;putInt(fixed(Offsets.offsets_56)+index*4,0);putFourthValue(index,0);putInt(Offsets.offsets_63+index*4,0);resetCharaNewFlag(index);int talents=catTalents(index).size();for(int i=0;i<talents;i++)setCatTalentLevel(index,i,0);for(int i=0;i<GameDataRules.dropPairCount();i++)if(GameDataRules.dropCat(i)==index)putInt(fixed(Offsets.offsets_59)+GameDataRules.dropSlot(i)*4,0);touchRankUpSale();refreshHash(); }
    public void setCatGuideCollected(int index,boolean value) { checkCat(index);if(value)unlockCatRaw(index);bytes[fixed(Offsets.offsets_57)+index]=(byte)(value?1:0);touchRankUpSale();refreshHash(); }
    public int specialSkillCount() { ensureCatProfile(); return 10; }
    public int specialSkillBaseLevel(int index) { return ushortAt(specialSkillUpgradeOffset(index)+2)+1; }
    public int specialSkillPlusLevel(int index) { return ushortAt(specialSkillUpgradeOffset(index)); }
    public void setSpecialSkillBaseLevel(int index,int value) { int offset=specialSkillUpgradeOffset(index);if(value<1||value>GameDataRules.specialSkillMaxBase(index))throw new IllegalArgumentException("Invalid base upgrade level");putShort(offset+2,value-1);if(index==0)putShort(fixed(Offsets.offsets_60),value-1);touchRankUpSale();refreshHash(); }
    public void setSpecialSkillPlusLevel(int index,int value) { int offset=specialSkillUpgradeOffset(index);if(value<0||value>GameDataRules.specialSkillMaxPlus(index))throw new IllegalArgumentException("Invalid plus upgrade level");putShort(offset,value);if(index==0)putShort(fixed(Offsets.offsets_61),value);touchRankUpSale();refreshHash(); }
    public List<TalentValue> catTalents(int catIndex) {
        checkCat(catIndex);int[] record=talentRecord(catIndex);List<TalentValue> out=new ArrayList<>();
        for(int i=0;i<record[1];i++){int id=intAt(record[0]+i*8),max=GameDataRules.talentMaxLevel(catIndex,id);if(max>0)out.add(new TalentValue(id,intAt(record[0]+i*8+4),max));}
        return out;
    }
    public void setCatTalentLevel(int catIndex,int talentIndex,int value) {
        checkCat(catIndex);int[] record=talentRecord(catIndex);int visible=0;
        for(int raw=0;raw<record[1];raw++){int id=intAt(record[0]+raw*8),max=GameDataRules.talentMaxLevel(catIndex,id);if(max<=0)continue;if(visible++==talentIndex){if(value<0||value>max)throw new IllegalArgumentException("Invalid talent level");unlockCatRaw(catIndex);putInt(record[0]+raw*8+4,value);touchRankUpSale();refreshHash();return;}}
        throw new IndexOutOfBoundsException();
    }
    public void setAllCatBaseLevels(int value) {
        ensureCatProfile();
        if (value < 1 || value > 60) throw new IllegalArgumentException("Invalid cat base level");
        for (int i = 0; i < catCount(); i++) {
            syncCatRankLimits(i); unlockCatForUpgrade(i);
            int target = Math.min(value, GameDataRules.catMaxBase(i));
            int maxUp = GameDataRules.catRankLimitBase(i, id -> true), base = 0, catseyes = 0;
            int catseyeLimit = Math.max(0, GameDataRules.catMaxCatseye(i) - GameDataRules.catMaxNoCatseye(i));
            for (int step = 0; step < target - 1; step++) {
                int currentMax = Math.min(GameDataRules.catOriginalMaxBase(i) + maxUp, GameDataRules.catMaxCatseye(i));
                boolean useEye = base >= currentMax && GameDataRules.catRarity(i) != 0 && base >= GameDataRules.catMaxNoCatseye(i)
                        && base < GameDataRules.catMaxCatseye(i) && catseyes < catseyeLimit;
                if (useEye) { base++; maxUp++; catseyes++; }
                else if (base < currentMax) base++;
                else break;
            }
            int finalMax = Math.min(GameDataRules.catOriginalMaxBase(i) + maxUp, GameDataRules.catMaxCatseye(i));
            if (catseyes >= catseyeLimit && catseyeLimit > 0) { maxUp++; catseyes++; }
            if (catseyeLimit > 0 && maxUp < GameDataRules.catMaxNoCatseye(i)) maxUp = GameDataRules.catMaxNoCatseye(i);
            if (GameDataRules.catMaxCatseye(i) >= 60 && catseyeLimit > 0) maxUp++;
            putShort(fixed(Offsets.offsets_39) + i * 4, base);
            putShort(fixed(Offsets.offsets_62) + i * 4, maxUp);
            int noEye = GameDataRules.catMaxNoCatseye(i);
            catseyes = noEye < 0 ? 0 : Math.max(0, base + 1 - noEye);
            int co = fixed(Offsets.offsets_63) + i * 4;
            bytes[co] = (byte)catseyes; bytes[co + 1] = (byte)(catseyes >>> 8);
            bytes[co + 2] = (byte)(catseyes >>> 16); bytes[co + 3] = (byte)(catseyes >>> 24);
        }
        touchRankUpSale(); refreshHash();
    }
    public void setAllCatPlusLevels(int value) { ensureCatProfile();if(value<0||value>90)throw new IllegalArgumentException("Invalid cat plus level");for(int i=0;i<catCount();i++){syncCatRankLimits(i);unlockCatRaw(i);putShort(fixed(Offsets.offsets_40)+i*4,Math.min(value,GameDataRules.catMaxPlus(i)));}touchRankUpSale();refreshHash(); }
    public void setAllCatGuideCollected(boolean value) { ensureCatProfile();for(int i=0;i<catCount();i++){if(value)unlockCatRaw(i);bytes[fixed(Offsets.offsets_57)+i]=(byte)(value?1:0);}touchRankUpSale();refreshHash(); }
    public void maxAllCatTalents() { ensureItemProfile();int table=talentTableOffset(),records=intAt(table),offset=table+4;for(int r=0;r<records;r++){int cat=intAt(offset),count=intAt(offset+4);offset+=8;boolean edited=false;for(int i=0;i<count;i++){int max=GameDataRules.talentMaxLevel(cat,intAt(offset+i*8));if(max>0){putInt(offset+i*8+4,max);edited=true;}}if(edited&&cat>=0&&cat<catCount())unlockCatRaw(cat);offset+=count*8;}touchRankUpSale();refreshHash(); }
    public static final class TalentValue { public final int id; public final int level; public final int maxLevel; TalentValue(int id,int level,int maxLevel){this.id=id;this.level=level;this.maxLevel=maxLevel;} }
    public int enemyGuideCount() { ensureItemProfile(); return 802; }
    public boolean enemyGuideUnlocked(int index) { if(index<0||index>=enemyGuideCount())throw new IndexOutOfBoundsException();return intAt(fixed(Offsets.offsets_64)+index*4)!=0; }
    public void setEnemyGuideUnlocked(int index,boolean value) { if(index<0||index>=enemyGuideCount())throw new IndexOutOfBoundsException();putInt(fixed(Offsets.offsets_64)+index*4,value?1:0);refreshHash(); }
    public void setAllEnemyGuide(boolean value) { for(int i=0;i<enemyGuideCount();i++)putInt(fixed(Offsets.offsets_64)+i*4,value?1:0);refreshHash(); }
    public int userRankRewardCount() { ensureItemProfile(); return intAt(fixed(Offsets.offsets_65)); }
    public boolean userRankRewardClaimed(int index) { int count=userRankRewardCount();if(index<0||index>=count)throw new IndexOutOfBoundsException();return byteAt(fixed(Offsets.offsets_66)+index)!=0; }
    public void setUserRankRewardClaimed(int index,boolean value) { int count=userRankRewardCount();if(index<0||index>=count)throw new IndexOutOfBoundsException();bytes[fixed(Offsets.offsets_66)+index]=(byte)(value?1:0);refreshHash(); }
    public void setAllUserRankRewards(boolean value) { int count=userRankRewardCount();for(int i=0;i<count;i++)bytes[fixed(Offsets.offsets_66)+i]=(byte)(value?1:0);refreshHash(); }
    public int knownUserRankRewardCount() { return Math.min(userRankRewardCount(),GameDataRules.rankGiftCount()); }
    public boolean userRankRewardEligible(int index) { if(index<0||index>=knownUserRankRewardCount())throw new IndexOutOfBoundsException();return GameDataRules.rankGiftThreshold(index)<=userRank(); }
    public void setEligibleUserRankRewardClaimed(int index,boolean value) { if(!userRankRewardEligible(index))throw new IllegalArgumentException("Reward is not unlocked");setUserRankRewardClaimed(index,value); }
    public void fixUserRankRewards() { int rank=userRank(),count=knownUserRankRewardCount();for(int i=0;i<count;i++)if(GameDataRules.rankGiftThreshold(i)>rank)bytes[fixed(Offsets.offsets_66)+i]=0;refreshHash(); }
    public int storyChapterCount() { ensureItemProfile(); return 9; }
    public int storyStageCount() { ensureItemProfile(); return 48; }
    public int storyClearTimes(int chapter,int stage) { int raw=storyInternalChapter(chapter);checkStoryStage(stage);return intAt(fixed(Offsets.offsets_28)+(raw*51+stage)*4); }
    public int storyTreasure(int chapter,int stage) { int raw=storyInternalChapter(chapter),stored=storyTreasureStorageStage(stage);return intAt(fixed(Offsets.offsets_67)+(raw*49+stored)*4); }
    public void setStoryClearTimes(int chapter,int stage,int value) { int raw=storyInternalChapter(chapter);checkStoryStage(stage);if(value<0||value>32767)throw new IllegalArgumentException("Invalid clears");putInt(fixed(Offsets.offsets_28)+(raw*51+stage)*4,value);putInt(fixed(Offsets.offsets_68)+raw*4,stage+1);refreshHash(); }
    public void setStoryTreasure(int chapter,int stage,int value) { int raw=storyInternalChapter(chapter),stored=storyTreasureStorageStage(stage);if(value<0||value>9999)throw new IllegalArgumentException("Invalid treasure");putInt(fixed(Offsets.offsets_67)+(raw*49+stored)*4,value);refreshHash(); }
    public void clearStoryChapter(int chapter,boolean cleared) { int raw=storyInternalChapter(chapter);for(int i=0;i<48;i++)putInt(fixed(Offsets.offsets_28)+(raw*51+i)*4,cleared?1:0);putInt(fixed(Offsets.offsets_68)+raw*4,48);refreshHash(); }
    public void setStoryChapterTreasures(int chapter,int value) { int raw=storyInternalChapter(chapter);if(value<0||value>9999)throw new IllegalArgumentException("Invalid treasure");for(int i=0;i<48;i++)putInt(fixed(Offsets.offsets_67)+(raw*49+i)*4,value);refreshHash(); }
    public int akuChapterCount() { ensureItemProfile(); return ushortAt(akuTableOffset()); }
    public int akuStageCount() { ensureItemProfile(); return byteAt(akuTableOffset()+2); }
    public int akuStarCount() { ensureItemProfile(); return byteAt(akuTableOffset()+3); }
    public int akuClearTimes(int chapter,int star,int stage) { return ushortAt(akuStageOffset(chapter,star,stage)); }
    public void setAkuClearTimes(int chapter,int star,int stage,int value) { if(value<0||value>32767)throw new IllegalArgumentException("Invalid clears");putShort(akuStageOffset(chapter,star,stage),value);refreshHash(); }
    public void setAkuProgress(int progress,int clearCount) { ensureItemProfile();int stages=akuStageCount();if(akuChapterCount()==0||akuStarCount()==0)throw new IllegalStateException("Aku data is unavailable");if(progress<0||progress>stages||clearCount<0||clearCount>32767)throw new IllegalArgumentException("Invalid Aku progress");for(int i=0;i<stages;i++)putShort(akuStageOffset(0,0,i),i<progress?clearCount:0);refreshHash(); }
    public void clearAkuChapter(int chapter,int star,boolean cleared) { int stages=akuStageCount();for(int i=0;i<stages;i++)putShort(akuStageOffset(chapter,star,i),cleared?1:0);refreshHash(); }
    public int missionCount() { ensureItemProfile(); return intAt(afterCannons(Offsets.offsets_69)); }
    public int[] missionIds() { return dictionaryKeys(missionDictionaryOffset(0)); }
    public int missionClearState(int missionId) { return dictionaryValue(missionDictionaryOffset(0),missionId); }
    public int missionRequirement(int missionId) { return dictionaryValue(missionDictionaryOffset(1),missionId); }
    public void setMissionClearState(int missionId,int value) { setDictionaryValue(missionDictionaryOffset(0),missionId,value);refreshHash(); }
    public void setMissionRequirement(int missionId,int value) { setDictionaryValue(missionDictionaryOffset(1),missionId,value);refreshHash(); }
    public void setMissionCompletion(int missionId,int state) { if(state!=0&&state!=2&&state!=4)throw new IllegalArgumentException("Invalid mission state");int target=GameDataRules.missionTarget(missionId);if(target<0)throw new IllegalArgumentException("Unknown mission condition");int clear=missionDictionaryOffset(0);dictionaryValue(clear,missionId);setDictionaryValue(clear,missionId,state);int requirements=missionDictionaryOffset(1);if(state==0){if(dictionaryContains(requirements,missionId))setDictionaryValue(requirements,missionId,0);}else upsertDictionaryValue(requirements,missionId,target);refreshHash(); }
    public int stageMapCount(StageMap type) { return mapLayout(type).maps; }
    public int stageMapStarCount(StageMap type,int map) { MapLayout l=mapLayout(type);checkMap(l,map,0,0,false);return Math.min(l.starsAt(map),configuredCrownCount(type,map)); }
    public int stageMapStageCount(StageMap type,int map,int star) { MapLayout l=mapLayout(type);checkVisibleMap(type,l,map,star,0,false);return l.stagesAt(map,star); }
    public int stageMapClearTimes(StageMap type,int map,int star,int stage) { MapLayout l=mapLayout(type);checkVisibleMap(type,l,map,star,stage,true);return l.shortValues?ushortAt(l.stageOffset(map,star,stage)):intAt(l.stageOffset(map,star,stage)); }
    public void setStageMapClearTimes(StageMap type,int map,int star,int stage,int value) { MapLayout l=mapLayout(type);checkVisibleMap(type,l,map,star,stage,true);if(value<0||(l.shortValues&&value>65535))throw new IllegalArgumentException("Invalid clears");if(l.shortValues)putShort(l.stageOffset(map,star,stage),value);else putInt(l.stageOffset(map,star,stage),value);if(l.mirrorStageBase>=0)putShort(l.mirrorOffset(map,star,stage),value);l.updateProgress(map,star,stage,value);refreshHash(); }
    public void clearStageMap(StageMap type,int map,boolean cleared) { MapLayout l=mapLayout(type);checkMap(l,map,0,0,false);int stars=stageMapStarCount(type,map);for(int star=0;star<stars;star++){int total=l.stagesAt(map,star);for(int stage=0;stage<total;stage++){int offset=l.stageOffset(map,star,stage),current=l.shortValues?ushortAt(offset):intAt(offset),value=cleared?(star==stars-1||current==0?1:current):0;if(l.shortValues)putShort(offset,value);else putInt(offset,value);if(cleared&&l.mirrorStageBase>=0){int mirror=l.mirrorOffset(map,star,stage),tries=ushortAt(mirror);putShort(mirror,tries==0?1:tries);}}l.setProgress(map,star,cleared?total:0);if(cleared)l.setUnlock(map,star,3);}if(cleared&&l.cascadeCompletion&&l.hasNextMap(map))l.setUnlock(map+1,0,1);refreshHash(); }
    public void unlockAkuRealm() { ensureItemProfile();for(int id:new int[]{255,256,257,258,265,266,268})clearEventMap(1,id,0);refreshHash(); }
    public int gamatotoDestination() { ensureItemProfile();return intAt(fixed(Offsets.offsets_70)); }
    public int gamatotoLevel() { int xp=gamatotoXp();for(int i=0;i<129;i++)if(xp<GameDataRules.GAMATOTO_XP[i])return i+1;return 130; }
    public void setGamatotoLevel(int level) { if(level<1||level>130)throw new IllegalArgumentException("Invalid Gamatoto level");setGamatotoXp(level==1?0:GameDataRules.GAMATOTO_XP[level-2]); }
    public void setGamatotoDestination(int value) { ensureItemProfile();putInt(fixed(Offsets.offsets_70),value);refreshHash(); }
    public int gamatotoHelperCount() { ensureItemProfile();return intAt(gamatotoHelperTableOffset()); }
    public int gamatotoHelper(int index) { int base=gamatotoHelperTableOffset(),count=intAt(base);if(index<0||index>=count)throw new IndexOutOfBoundsException();return intAt(base+4+index*4); }
    public void setGamatotoHelper(int index,int id) { int base=gamatotoHelperTableOffset(),count=intAt(base);if(index<0||index>=count)throw new IndexOutOfBoundsException();putInt(base+4+index*4,id);refreshHash(); }
    public int[] gamatotoHelperRarityAmounts() { int[] out=new int[5];for(int i=0;i<gamatotoHelperCount();i++){int rarity=GameDataRules.gamatotoHelperRarity(gamatotoHelper(i));if(rarity>=0)out[rarity]++;}return out; }
    public void setGamatotoHelperRarityAmounts(int[] amounts) { ensureItemProfile();if(amounts==null||amounts.length!=5)throw new IllegalArgumentException("Invalid helper rarities");int total=0;for(int i=0;i<amounts.length;i++){if(amounts[i]<0||amounts[i]>GameDataRules.gamatotoHelperRarityCapacity(i))throw new IllegalArgumentException("Invalid helper amount");total+=amounts[i];}if(total>10)throw new IllegalArgumentException("Too many helpers");int table=gamatotoHelperTableOffset(),old=intAt(table);splice(table+4,old*4,total*4);putInt(table,total);int at=table+4;for(int rarity=0;rarity<amounts.length;rarity++)for(int i=0;i<amounts[rarity];i++){putInt(at,GameDataRules.gamatotoFirstHelper(rarity)+i);at+=4;}refreshHash(); }
    public int baseMaterialCount() { ensureItemProfile();return intAt(baseMaterialTableOffset()); }
    public int baseMaterial(int index) { int base=baseMaterialTableOffset(),count=intAt(base);if(index<0||index>=count)throw new IndexOutOfBoundsException();return intAt(base+4+index*4); }
    public void setBaseMaterial(int index,int value) { int base=baseMaterialTableOffset(),count=intAt(base);if(index<0||index>=count)throw new IndexOutOfBoundsException();checkAmount("Base Material",value,9999);putInt(base+4+index*4,value);refreshHash(); }
    public int ototoEngineers() { ensureItemProfile();return intAt(cannonBase()-4); }
    public void setOtotoEngineers(int value) { ensureItemProfile();if(value<0||value>5)throw new IllegalArgumentException("Invalid engineer count");putInt(cannonBase()-4,value);refreshHash(); }
    public int cannonCount() { ensureItemProfile();return intAt(cannonBase()); }
    public int cannonId(int index) { return cannonEntry(index)[0]; }
    public int cannonPartCount(int index) { return cannonEntry(index)[2]; }
    public int cannonDevelopment(int index) { int[] e=cannonEntry(index);return intAt(e[1]+8); }
    public int cannonPartLevel(int index,int part) { int[] e=cannonEntry(index);if(part<0||part>=e[2])throw new IndexOutOfBoundsException();return intAt(e[1]+12+part*4); }
    public void setCannonDevelopment(int index,int value) { int[] e=cannonEntry(index);if(index==0)throw new IllegalArgumentException("Cannon 0 has no development stage");if(value<0||value>3)throw new IllegalArgumentException("Invalid cannon development");putInt(e[1]+8,value);refreshHash(); }
    public void setCannonPartLevel(int index,int part,int value) { int[] e=cannonEntry(index);if(part<0||part>=e[2])throw new IndexOutOfBoundsException();int max=GameDataRules.cannonMaxLevel(e[0],part)-(part==0?1:0);if(value<0||value>max)throw new IllegalArgumentException("Invalid cannon level");putInt(e[1]+8,Math.max(intAt(e[1]+8),3));putInt(e[1]+12+part*4,value);refreshHash(); }
    public boolean catShrineGone() { ensureItemProfile();return byteAt(catShrineBase()+17)!=0; }
    public void setCatShrineGone(boolean value) { ensureItemProfile();int base=catShrineBase();if(!value){putDouble(base+1,0);putDouble(base+9,0);}bytes[base+17]=(byte)(value?1:0);putInt(catShrineDialogsOffset(),catShrineLevel()-1);refreshHash(); }
    public long catShrineXp() { ensureItemProfile();return rawLongAt(catShrineXpOffset()); }
    public int catShrineLevel() { long xp=catShrineXp();for(int i=0;i<GameDataRules.SHRINE_XP.length;i++)if(xp<GameDataRules.SHRINE_XP[i])return i+1;return GameDataRules.SHRINE_XP.length; }
    public void setCatShrineLevel(int level) { if(level<1||level>GameDataRules.SHRINE_XP.length)throw new IllegalArgumentException("Invalid shrine level");setCatShrineXp(level==1?0:GameDataRules.SHRINE_XP[level-2]); }
    public void setCatShrineXp(long value) { ensureItemProfile();if(value<0||value>575600000L)throw new IllegalArgumentException("Invalid shrine XP");putLong(catShrineXpOffset(),value);putInt(catShrineDialogsOffset(),catShrineLevel()-1);refreshHash(); }
    public int catShrineDialogs() { ensureItemProfile();return intAt(catShrineDialogsOffset()); }
    public void setCatShrineDialogs(int value) { ensureItemProfile();putInt(catShrineDialogsOffset(),value);refreshHash(); }
    public double endlessBattleDurationMinutes(int index) { int base=endlessBattleOffset(index);if(byteAt(base)==0)return 0;double start=Double.longBitsToDouble(rawLongAt(base+3)),end=Double.longBitsToDouble(rawLongAt(base+11));if(Double.isInfinite(end))return Double.POSITIVE_INFINITY;return (end-start)/60.0; }
    public void setEndlessBattleDurationMinutes(int index,double minutes) { int base=endlessBattleOffset(index);if(Double.isNaN(minutes)||minutes<0)throw new IllegalArgumentException("Invalid endless duration");double now=System.currentTimeMillis()/1000.0;bytes[base]=1;bytes[base+1]=1;bytes[base+2]=0;putLong(base+3,Double.doubleToRawLongBits(now));putLong(base+11,Double.doubleToRawLongBits(Double.isInfinite(minutes)?Double.POSITIVE_INFINITY:now+minutes*60.0));refreshHash(); }
    public boolean endlessBattleItem(int index) { return endlessBattleDurationMinutes(index)>0||Double.isInfinite(endlessBattleDurationMinutes(index)); }
    public void setEndlessBattleItem(int index,boolean value) { if(value)setEndlessBattleDurationMinutes(index,Double.POSITIVE_INFINITY);else{bytes[endlessBattleOffset(index)]=0;refreshHash();} }
    public int luckyTicketCount() { ensureItemProfile();return 55; }
    public int luckyTicket(int index) { return fixedArrayValue(afterCannons(Offsets.offsets_71),55,index); }
    public void setLuckyTicket(int index,int value) { checkAmount("Lucky Ticket",value,9999);setFixedArrayValue(afterCannons(Offsets.offsets_71),55,index,value); }
    public int eventTicketCount() { ensureItemProfile();return 62; }
    public int eventTicket(int index) { return index<60?fixedArrayValue(fixed(Offsets.offsets_72),60,index):fixedArrayValue(eventCapsules2Offset(),2,index-60); }
    public void setEventTicket(int index,int value) { if(index<0||index>=62)throw new IndexOutOfBoundsException();checkAmount("Event Ticket",value,9999);if(index<60)setFixedArrayValue(fixed(Offsets.offsets_72),60,index,value);else setFixedArrayValue(eventCapsules2Offset(),2,index-60,value); }
    public int lineupCount() { ensureItemProfile();return byteAt(fixed(Offsets.offsets_73)); }
    public int lineupCat(int lineup,int slot) { checkLineup(lineup,slot);return intAt(fixed(Offsets.offsets_74)+(lineup*10+slot)*4); }
    public void setLineupCat(int lineup,int slot,int catId) { checkLineup(lineup,slot);putInt(fixed(Offsets.offsets_74)+(lineup*10+slot)*4,catId);refreshHash(); }
    public int unlockedLineups() { ensureItemProfile();return byteAt(fixed(Offsets.offsets_75)); }
    public int unlockableLineupCount() { ensureItemProfile();return byteAt(findInt(90900)+4); }
    public void setUnlockedLineups(int value) { ensureItemProfile();if(value<0||value>unlockableLineupCount())throw new IllegalArgumentException("Invalid lineup count");bytes[fixed(Offsets.offsets_75)]=(byte)value;refreshHash(); }
    public int restartPackState() { ensureItemProfile();return intAt(afterCannons(Offsets.offsets_76)); }
    public void setRestartPackState(int value) { ensureItemProfile();putInt(afterCannons(Offsets.offsets_76),value);refreshHash(); }
    public int goldPassOfficerId() { ensureItemProfile();return intAt(goldPassBase()); }
    public void setGoldPassOfficerId(int value) { ensureItemProfile();putInt(goldPassBase(),value);refreshHash(); }
    public int goldPassRenewals() { ensureItemProfile();return intAt(goldPassBase()+4); }
    public void setGoldPassRenewals(int value) { ensureItemProfile();putInt(goldPassBase()+4,value);refreshHash(); }
    public long goldPassDate(int index) { ensureItemProfile();if(index<0||index>=6)throw new IndexOutOfBoundsException();return longAt(goldPassBase()+8+index*8); }
    public void setGoldPassDate(int index,long value) { ensureItemProfile();if(index<0||index>=6)throw new IndexOutOfBoundsException();putLong(goldPassBase()+8+index*8,Double.doubleToRawLongBits((double)value));refreshHash(); }
    public int goldPassStateUpdates() { ensureItemProfile();return intAt(goldPassBase()+64); }
    public void setGoldPassStateUpdates(int value) { ensureItemProfile();putInt(goldPassBase()+64,value);refreshHash(); }
    public void grantGoldPass(int officerId,long startTime,int days) { ensureItemProfile();if(officerId<1||days<1)throw new IllegalArgumentException("Invalid Gold Pass");int base=goldPassBase();clearGoldPassClaims();long end=startTime+days*86400L,totalEnd=startTime+days*2L*86400L;putInt(base,officerId);putInt(base+4,2);putDouble(base+8,startTime);putDouble(base+16,end);putDouble(base+24,end);putDouble(base+32,totalEnd);putDouble(base+40,startTime);putDouble(base+48,totalEnd);putDouble(base+56,startTime);putInt(base+64,2);putDouble(base+68,end);int tail=base+80;putDouble(tail,0);bytes[tail+8]=1;bytes[tail+9]=0;refreshHash(); }
    public void removeGoldPass() { ensureItemProfile();int base=goldPassBase();clearGoldPassClaims();putInt(base,-1);putInt(base+4,0);for(int i=0;i<7;i++)putDouble(base+8+i*8,0);putInt(base+64,0);putDouble(base+68,0);int tail=base+80;putDouble(tail,0);bytes[tail+8]=0;bytes[tail+9]=0;refreshHash(); }
    public int officerPassCatId() { ensureItemProfile();int offset=afterCannons(Offsets.offsets_77);return ushortAt(offset)==65535?-1:ushortAt(offset); }
    public void setOfficerPassCatId(int value) { ensureItemProfile();if(value< -1||value>65534)throw new IllegalArgumentException("Invalid cat ID");putShort(afterCannons(Offsets.offsets_77),value<0?65535:value);refreshHash(); }
    public int officerPassCatForm() { ensureItemProfile();return ushortAt(afterCannons(Offsets.offsets_78)); }
    public void setOfficerPassCatForm(int value) { ensureItemProfile();if(value<0||value>65535)throw new IllegalArgumentException("Invalid cat form");putShort(afterCannons(Offsets.offsets_78),value);refreshHash(); }
    public int medalCount() { ensureItemProfile();return ushortAt(medalBaseOffset()+12); }
    public int medalId(int index) { int count=medalCount(),base=medalBaseOffset()+14;if(index<0||index>=count)throw new IndexOutOfBoundsException();return ushortAt(base+index*2); }
    public void addMedal(int id) { ensureItemProfile();if(id<0||id>=127)throw new IllegalArgumentException("Invalid medal");for(int i=0;i<medalCount();i++)if(medalId(i)==id)return;int medalBase=medalBaseOffset(),count=ushortAt(medalBase+12),list=medalBase+14;splice(list+count*2,0,2);putShort(list+count*2,id);putShort(medalBase+12,count+1);int dictionary=list+(count+1)*2,entries=ushortAt(dictionary),end=dictionary+2+entries*3;splice(end,0,3);putShort(end,id);bytes[end+2]=0;putShort(dictionary,entries+1);refreshHash(); }
    public void removeMedal(int index) { int medalBase=medalBaseOffset(),count=ushortAt(medalBase+12),list=medalBase+14;if(index<0||index>=count)throw new IndexOutOfBoundsException();int id=ushortAt(list+index*2);splice(list+index*2,2,0);putShort(medalBase+12,count-1);int dictionary=list+(count-1)*2,entries=ushortAt(dictionary);for(int i=0;i<entries;i++)if(ushortAt(dictionary+2+i*3)==id){splice(dictionary+2+i*3,3,0);putShort(dictionary,entries-1);break;}refreshHash(); }
    public int talentOrbCount() { ensureItemProfile();return ushortAt(talentOrbTableOffset()); }
    public int talentOrbId(int index) { int table=talentOrbTableOffset(),count=ushortAt(table);if(index<0||index>=count)throw new IndexOutOfBoundsException();return ushortAt(table+2+index*4); }
    public int talentOrbAmount(int index) { int table=talentOrbTableOffset(),count=ushortAt(table);if(index<0||index>=count)throw new IndexOutOfBoundsException();return ushortAt(table+4+index*4); }
    public void setTalentOrbAmount(int index,int value) { int table=talentOrbTableOffset(),count=ushortAt(table);if(index<0||index>=count)throw new IndexOutOfBoundsException();checkAmount("Talent Orb",value,998);putShort(table+4+index*4,value);refreshHash(); }
    public void addTalentOrb(int id,int amount) { ensureItemProfile();if(id<0||id>65535)throw new IllegalArgumentException("Invalid orb ID");checkAmount("Talent Orb",amount,998);int table=talentOrbTableOffset(),count=ushortAt(table),base=table+2;for(int i=0;i<count;i++)if(ushortAt(base+i*4)==id){putShort(base+i*4+2,amount);refreshHash();return;}splice(base+count*4,0,4);putShort(base+count*4,id);putShort(base+2+count*4,amount);putShort(table,count+1);refreshHash(); }
    public void removeTalentOrb(int index) { int table=talentOrbTableOffset(),count=ushortAt(table),base=table+2;if(index<0||index>=count)throw new IndexOutOfBoundsException();splice(base+index*4,4,0);putShort(table,count-1);refreshHash(); }
    public int dojoScore() { ensureItemProfile();int table=dojoScoreTableOffset(),chapters=intAt(table),p=table+4;for(int c=0;c<chapters;c++){int chapter=intAt(p),stages=intAt(p+4);p+=8;if(chapter==0)for(int s=0;s<stages;s++)if(intAt(p+s*8)==0)return intAt(p+s*8+4);p+=stages*8;}return 0; }
    public void setDojoScore(int value) { ensureItemProfile();int table=dojoScoreTableOffset(),chapters=intAt(table),p=table+4;for(int c=0;c<chapters;c++){int chapter=intAt(p),stages=intAt(p+4);if(chapter==0){int entries=p+8;for(int s=0;s<stages;s++)if(intAt(entries+s*8)==0){putInt(entries+s*8+4,value);refreshHash();return;}int end=entries+stages*8;splice(end,0,8);putInt(p+4,stages+1);putInt(end,0);putInt(end+4,value);refreshHash();return;}p+=8+stages*8;}int end=p;splice(end,0,16);putInt(table,chapters+1);putInt(end,0);putInt(end+4,1);putInt(end+8,0);putInt(end+12,value);refreshHash(); }
    public int dojoRanking() { ensureItemProfile();return intAt(afterCannons(Offsets.offsets_79)); }
    public void setDojoRanking(int value) { ensureItemProfile();putInt(afterCannons(Offsets.offsets_79),value);refreshHash(); }
    public int timedScoreChapterCount() { ensureItemProfile();return 3; }
    public int timedScoreStageCount() { ensureItemProfile();return 48; }
    public int timedScore(int chapter,int stage) { return intAt(itfTimedScoreOffset(chapter,stage)); }
    public void setTimedScore(int chapter,int stage,int value) { checkAmount("ITF timed score",value,9999);putInt(itfTimedScoreOffset(chapter,stage),value);refreshHash(); }
    public int enigmaEnergy() { ensureItemProfile();return intAt(enigmaBaseOffset()); }
    public void setEnigmaEnergy(int value) { ensureItemProfile();putInt(enigmaBaseOffset(),value);refreshHash(); }
    public int enigmaLevel() { ensureItemProfile();return byteAt(enigmaBaseOffset()+8); }
    public void setEnigmaLevel(int value) { ensureItemProfile();if(value<0||value>255)throw new IllegalArgumentException("Invalid enigma level");bytes[enigmaBaseOffset()+8]=(byte)value;refreshHash(); }
    public int enigmaStageCount() { ensureItemProfile();return byteAt(enigmaBaseOffset()+11); }
    public int enigmaStageId(int index) { return intAt(enigmaStageOffset(index)+4); }
    public int enigmaStageLevel(int index) { return intAt(enigmaStageOffset(index)); }
    public void setEnigmaStageLevel(int index,int value) { putInt(enigmaStageOffset(index),value);refreshHash(); }
    public int enigmaStageDecoding(int index) { return byteAt(enigmaStageOffset(index)+8); }
    public void setEnigmaStageDecoding(int index,int value) { if(value<0||value>255)throw new IllegalArgumentException("Invalid decoding state");bytes[enigmaStageOffset(index)+8]=(byte)value;refreshHash(); }
    public long enigmaStageStartTime(int index) { return longAt(enigmaStageOffset(index)+9); }
    public void setEnigmaStageStartTime(int index,long value) { putLong(enigmaStageOffset(index)+9,Double.doubleToRawLongBits((double)value));refreshHash(); }
    public void addEnigmaStage(int stageId,int level,int decoding,long startTime) { int countOffset=enigmaBaseOffset()+11,count=byteAt(countOffset);if(count>=255||decoding<0||decoding>255)throw new IllegalArgumentException("Invalid Enigma stage");int offset=countOffset+1+count*17;splice(offset,0,17);putInt(offset,level);putInt(offset+4,stageId);bytes[offset+8]=(byte)decoding;putLong(offset+9,Double.doubleToRawLongBits((double)startTime));bytes[countOffset]=(byte)(count+1);refreshHash(); }
    public void removeEnigmaStage(int index) { int countOffset=enigmaBaseOffset()+11,count=byteAt(countOffset);if(index<0||index>=count)throw new IndexOutOfBoundsException();int offset=countOffset+1+index*17;splice(offset,17,0);bytes[countOffset]=(byte)(count-1);refreshHash(); }
    public void addActiveEnigmaStage(int localId,long startTime) { if(localId<0||localId>=73)throw new IllegalArgumentException("Invalid Enigma ID");int absoluteId=25000+localId;addEnigmaStage(absoluteId,3,2,startTime);upsertDictionaryValue(eventCompletionDictionaryOffset(),absoluteId,0);refreshHash(); }
    public void clearActiveEnigmaStages() { int[] ids=new int[enigmaStageCount()];for(int i=0;i<ids.length;i++)ids[i]=enigmaStageId(i);for(int i=ids.length-1;i>=0;i--)removeEnigmaStage(i);int dictionary=eventCompletionDictionaryOffset();for(int id:ids)upsertDictionaryValue(dictionary,id,0);refreshHash(); }
    public int storageCount() { ensureItemProfile();return ushortAt(storageTableOffset()); }
    public int storageItemId(int slot) { checkStorage(slot);return intAt(storageTableOffset()+2+slot*4); }
    public int storageItemType(int slot) { checkStorage(slot);return intAt(storageTableOffset()+2+storageCount()*4+slot*4); }
    public void setStorageItem(int slot,int type,int id) { checkStorage(slot);if(type<0||type>3)throw new IllegalArgumentException("Invalid storage type");int table=storageTableOffset(),count=storageCount();putInt(table+2+slot*4,id);putInt(table+2+count*4+slot*4,type);refreshHash(); }
    public void clearStorage() { int table=storageTableOffset(),count=storageCount();for(int i=0;i<count;i++){putInt(table+2+i*4,0);putInt(table+2+count*4+i*4,0);}refreshHash(); }
    public void addStorageCat(int catId) { if(catId<0||catId>=873)throw new IllegalArgumentException("Invalid cat ID");addStorageItem(1,catId); }
    public void addStorageSpecialSkill(int skillId) { if(skillId<0||skillId>=10)throw new IllegalArgumentException("Invalid special skill ID");addStorageItem(2,skillId); }
    public void addStorageCats(int catId,int quantity) { if(catId<0||catId>=873||quantity<1)throw new IllegalArgumentException("Invalid storage cats");ensureStorageSpace(quantity);for(int i=0;i<quantity;i++)addStorageItem(1,catId); }
    public void addStorageSpecialSkills(int skillId,int quantity) { if(skillId<0||skillId>=10||quantity<1)throw new IllegalArgumentException("Invalid storage skills");ensureStorageSpace(quantity);for(int i=0;i<quantity;i++)addStorageItem(2,skillId); }
    public int occupiedStorageCount() { int count=0;for(int i=0;i<storageCount();i++)if(storageItemType(i)!=0)count++;return count; }
    public int occupiedStorageSlot(int index) { if(index<0)throw new IndexOutOfBoundsException();for(int slot=0;slot<storageCount();slot++)if(storageItemType(slot)!=0&&index--==0)return slot;throw new IndexOutOfBoundsException(); }
    public void removeOccupiedStorageItem(int index) { setStorageItem(occupiedStorageSlot(index),0,0); }
    public int firstEmptyStorageSlot() { for(int i=0;i<storageCount();i++)if(storageItemType(i)==0)return i;return -1; }
    public int rareTicketTradeProgress() { ensureItemProfile();return intAt(fixed(Offsets.offsets_80)); }
    public void tradeRareTickets(int amount) { ensureItemProfile();if(amount<0)throw new IllegalArgumentException("Invalid trade amount");int slot=-1;for(int i=0;i<storageCount();i++)if(storageItemType(i)==0||(storageItemType(i)==2&&storageItemId(i)==1)){slot=i;break;}if(slot<0)throw new IllegalStateException("Storage is full");setStorageItem(slot,2,1);putInt(fixed(Offsets.offsets_80),amount*5);refreshHash(); }
    public int schemeToObtainCount() { ensureItemProfile();return intAt(schemeTableOffset()); }
    public int schemeToObtainId(int index) { int table=schemeTableOffset(),count=intAt(table);if(index<0||index>=count)throw new IndexOutOfBoundsException();return intAt(table+4+index*4); }
    public int schemeReceivedCount() { ensureItemProfile();int table=schemeTableOffset(),first=intAt(table);return intAt(table+4+first*4); }
    public int schemeReceivedId(int index) { int table=schemeTableOffset(),first=intAt(table),count=intAt(table+4+first*4),base=table+8+first*4;if(index<0||index>=count)throw new IndexOutOfBoundsException();return intAt(base+index*4); }
    public void addSchemeItem(int id) { ensureItemProfile();if(!GameDataRules.validSchemeItem(id))throw new IllegalArgumentException("Invalid scheme item");removeSchemeReceivedId(id);for(int i=0;i<schemeToObtainCount();i++)if(schemeToObtainId(i)==id){refreshHash();return;}int table=schemeTableOffset(),count=intAt(table),offset=table+4+count*4;splice(offset,0,4);putInt(offset,id);putInt(table,count+1);refreshHash(); }
    public void removeSchemeItem(int id) { ensureItemProfile();if(!GameDataRules.validSchemeItem(id))throw new IllegalArgumentException("Invalid scheme item");int table=schemeTableOffset(),count=intAt(table);for(int i=0;i<count;i++)if(schemeToObtainId(i)==id){splice(table+4+i*4,4,0);putInt(table,count-1);break;}removeSchemeReceivedId(id);refreshHash(); }
    public void fixTimeErrors(long unixSeconds) { ensureItemProfile();java.time.ZonedDateTime now=java.time.Instant.ofEpochSecond(unixSeconds).atZone(java.time.ZoneId.systemDefault());putInt(fixed(Offsets.offsets_20),now.getYear());putInt(fixed(Offsets.offsets_81),now.getMonthValue());putInt(fixed(Offsets.offsets_82),now.getDayOfMonth());putInt(fixed(Offsets.offsets_83),now.getHour());putInt(fixed(Offsets.offsets_84),now.getMinute());putInt(fixed(Offsets.offsets_85),now.getSecond());putLong(Offsets.offsets_86,Double.doubleToRawLongBits((double)unixSeconds));putLong(accountCreatedAtOffset(),Double.doubleToRawLongBits((double)unixSeconds));refreshHash(); }
    public void fixOtotoValues() { int base=cannonBase(),length=cannonTableLength();splice(base,length,5);for(int i=0;i<5;i++)bytes[base+i]=0;refreshHash(); }
    public void fixOfficerPass() { ensureItemProfile();int play=inquiryCodeOffset()+13;putInt(play,0);putShort(afterCannons(Offsets.offsets_77),0);putShort(afterCannons(Offsets.offsets_78),0);removeGoldPass();refreshHash(); }
    public int gamblingStartCount() { return gamblingStartCount(GamblingTable.WILDCAT_SLOTS); }
    public int gamblingStartCount(GamblingTable type) { return ushortAt(gamblingStartTableOffset(type)); }
    public int gamblingStartKey(int index) { return gamblingStartKey(GamblingTable.WILDCAT_SLOTS,index); }
    public int gamblingStartKey(GamblingTable type,int index) { int count=gamblingStartCount(type);if(index<0||index>=count)throw new IndexOutOfBoundsException();return ushortAt(gamblingStartTableOffset(type)+2+index*6); }
    public int gamblingStartDate(int index) { return gamblingStartDate(GamblingTable.WILDCAT_SLOTS,index); }
    public int gamblingStartDate(GamblingTable type,int index) { int count=gamblingStartCount(type);if(index<0||index>=count)throw new IndexOutOfBoundsException();return intAt(gamblingStartTableOffset(type)+4+index*6); }
    public void setGamblingStartDate(int index,int value) { setGamblingStartDate(GamblingTable.WILDCAT_SLOTS,index,value); }
    public void setGamblingStartDate(GamblingTable type,int index,int value) { int count=gamblingStartCount(type);if(index<0||index>=count)throw new IndexOutOfBoundsException();putInt(gamblingStartTableOffset(type)+4+index*6,value);refreshHash(); }
    public void addGamblingStart(GamblingTable type,int key,int date) { if(key<0||key>65535)throw new IllegalArgumentException("Invalid event ID");int count=gamblingStartCount(type);for(int i=0;i<count;i++)if(gamblingStartKey(type,i)==key){setGamblingStartDate(type,i,date);return;}int table=gamblingStartTableOffset(type),offset=table+2+count*6;splice(offset,0,6);putShort(offset,key);putInt(offset+2,date);putShort(table,count+1);refreshHash(); }
    public void removeGamblingStart(GamblingTable type,int index) { int count=gamblingStartCount(type),table=gamblingStartTableOffset(type);if(index<0||index>=count)throw new IndexOutOfBoundsException();splice(table+2+index*6,6,0);putShort(table,count-1);refreshHash(); }
    public void resetGambling(GamblingTable type) { int base=gamblingTableOffset(type),length=gamblingTableLength(base);splice(base,length,6);for(int i=0;i<6;i++)bytes[base+i]=0;refreshHash(); }
    public int outbreakChapterCount() { ensureItemProfile();return intAt(outbreakTableOffset()); }
    public int outbreakChapterId(int index) { return outbreakChapter(index)[0]; }
    public int outbreakStageCount(int chapterIndex) { return outbreakChapter(chapterIndex)[2]; }
    public int outbreakStageId(int chapterIndex,int stageIndex) { int[] c=outbreakChapter(chapterIndex);if(stageIndex<0||stageIndex>=c[2])throw new IndexOutOfBoundsException();return intAt(c[1]+8+stageIndex*5); }
    public boolean outbreakCleared(int chapterIndex,int stageIndex) { int[] c=outbreakChapter(chapterIndex);if(stageIndex<0||stageIndex>=c[2])throw new IndexOutOfBoundsException();return byteAt(c[1]+12+stageIndex*5)!=0; }
    public boolean currentOutbreakCleared(int chapterId,int stageId) { int table=currentOutbreakTableOffset(),chapters=intAt(table),offset=table+4;for(int chapter=0;chapter<chapters;chapter++){int id=intAt(offset),stages=intAt(offset+4);offset+=8;for(int stage=0;stage<stages;stage++){if(id==chapterId&&intAt(offset)==stageId)return byteAt(offset+4)!=0;offset+=5;}}return false; }
    public void setOutbreakCleared(int chapterIndex,int stageIndex,boolean value) { int[] c=outbreakChapter(chapterIndex);if(stageIndex<0||stageIndex>=c[2])throw new IndexOutOfBoundsException();bytes[c[1]+12+stageIndex*5]=(byte)(value?1:0);if(value)clearCurrentOutbreak(c[0],outbreakStageId(chapterIndex,stageIndex));refreshHash(); }
    public void setOutbreakChapterCleared(int chapterIndex,boolean value) { int[] c=outbreakChapter(chapterIndex);for(int i=0;i<c[2];i++){bytes[c[1]+12+i*5]=(byte)(value?1:0);if(value)clearCurrentOutbreak(c[0],outbreakStageId(chapterIndex,i));}refreshHash(); }
    public void unlockAllCats() { ensureCatProfile();for(int i=0;i<873;i++)unlockCatRaw(i);touchRankUpSale();refreshHash(); }
    public void unlockAllObtainableCats() { ensureCatProfile();for(int i=0;i<873;i++)if(GameDataRules.catObtainable(i))unlockCatRaw(i);touchRankUpSale();refreshHash(); }
    public void removeAllCats() { ensureCatProfile(); for(int i=0;i<873;i++) putInt(fixed(Offsets.offsets_38)+i*4,0);touchRankUpSale();refreshHash(); }
    public void resetAllCats() { ensureCatProfile();for(int i=0;i<873;i++){putInt(fixed(Offsets.offsets_38)+i*4,0);putShort(fixed(Offsets.offsets_40)+i*4,0);putShort(fixed(Offsets.offsets_39)+i*4,0);putInt(fixed(Offsets.offsets_55)+i*4,0);putInt(fixed(Offsets.offsets_58)+i*4,0);putFormValue(i,0);bytes[fixed(Offsets.offsets_57)+i]=0;putFourthValue(i,0);putInt(Offsets.offsets_63+i*4,0);}resetAllCharaNewFlags();int table=talentTableOffset(),records=intAt(table),talents=table+4;for(int r=0;r<records;r++){int count=intAt(talents+4);talents+=8;for(int i=0;i<count;i++)putInt(talents+i*8+4,0);talents+=count*8;}for(int i=0;i<GameDataRules.dropPairCount();i++)putInt(fixed(Offsets.offsets_59)+GameDataRules.dropSlot(i)*4,0);touchRankUpSale();refreshHash(); }
    public void unlockTrueForms() { setTrueForms(false); }
    public void forceTrueForms() { setTrueForms(true); }
    private void setTrueForms(boolean force) { ensureCatProfile();for(int i=0;i<873;i++){int forms=GameDataRules.totalForms(i),value=(force||forms>=3)?3:0;if(value>0)unlockCatRaw(i);putFormValue(i,value);putInt(fixed(Offsets.offsets_55)+i*4,force||forms>=3?2:forms==2?1:0);}touchRankUpSale();refreshHash(); }
    public void removeTrueForms() { ensureCatProfile(); for(int i=0;i<873;i++){ putInt(fixed(Offsets.offsets_87)+i*4,0);putInt(fixed(Offsets.offsets_56)+i*4,0);int current=intAt(fixed(Offsets.offsets_55)+i*4);putInt(fixed(Offsets.offsets_55)+i*4,Math.min(current,1)); }touchRankUpSale();refreshHash(); }
    public void unlockFourthForms() { setFourthForms(false); }
    public void forceFourthForms() { setFourthForms(true); }
    private void setFourthForms(boolean force) { ensureCatProfile();for(int i=0;i<873;i++){int forms=GameDataRules.totalForms(i);if(force||forms>=4){unlockCatRaw(i);putFormValue(i,3);putInt(fixed(Offsets.offsets_55)+i*4,3);putFourthValue(i,2);}else if(forms>=3){unlockCatRaw(i);putFormValue(i,3);putInt(fixed(Offsets.offsets_55)+i*4,2);putFourthValue(i,0);}else if(forms==2){putFormValue(i,0);putInt(fixed(Offsets.offsets_55)+i*4,1);putFourthValue(i,0);}else{putFormValue(i,0);putInt(fixed(Offsets.offsets_55)+i*4,0);putFourthValue(i,0);}}touchRankUpSale();refreshHash(); }
    public void removeFourthForms() { ensureCatProfile(); for(int i=0;i<873;i++){ int current=intAt(fixed(Offsets.offsets_55)+i*4); putInt(fixed(Offsets.offsets_55)+i*4,Math.min(current,2)); putInt(fixed(Offsets.offsets_56)+i*4,0); }touchRankUpSale();refreshHash(); }

    public boolean checksumValid() { return hasValidHash(bytes, region, integerStart); }
    public String checksum() { return new String(bytes, bytes.length-Offsets.offsets_130, 32, java.nio.charset.StandardCharsets.US_ASCII); }

    private static boolean hasValidHash(byte[] source, Region region, int start) {
        if (start + 44 > source.length || source.length < 32) return false;
        String expected = md5(region.salt(), source, 0, source.length-Offsets.offsets_130);
        String actual = new String(source, source.length-Offsets.offsets_130, 32, java.nio.charset.StandardCharsets.US_ASCII);
        return expected.equalsIgnoreCase(actual);
    }

    private void refreshHash() {
        byte[] digestInput = Arrays.copyOf(bytes, bytes.length-Offsets.offsets_130);
        byte[] digest = md5Bytes(region.salt(), digestInput);
        String value = hex(digest);
        System.arraycopy(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, bytes, bytes.length-Offsets.offsets_130, 32);
    }

    private int intAt(int offset) {
        if (offset < 0 || offset + 4 > bytes.length-Offsets.offsets_130) throw new IllegalStateException("Save field is unavailable");
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8) | ((bytes[offset + 2] & 255) << 16) | (bytes[offset + 3] << 24);
    }
    private int findIntNear(int value,int estimate,int radius) { int from=Math.max(0,estimate-radius),to=Math.min(bytes.length-36,estimate+radius);for(int offset=from;offset<=to;offset++)if(intAt(offset)==value)return offset;throw new IllegalStateException("Save marker is unavailable: "+value); }
    private int findInt(int value) { for(int offset=4;offset<bytes.length-35;offset++)if(intAt(offset)==value)return offset;throw new IllegalStateException("Save marker is unavailable: "+value); }
    private static void writeInt(byte[] target,int offset,int value) { target[offset]=(byte)value;target[offset+1]=(byte)(value>>8);target[offset+2]=(byte)(value>>16);target[offset+3]=(byte)(value>>24); }
    private enum ProfileField { NORMAL_TICKETS, RARE_TICKETS, PLATINUM_TICKETS, LEGEND_TICKETS, PLATINUM_SHARDS, NP, LEADERSHIP }
    private int profiledInt(ProfileField field) { int offset = profileOffset(field); if (offset < 0) throw new UnsupportedOperationException("No item profile for this save version"); return intAt(offset); }
    private void setProfiledInt(ProfileField field, int value) { int offset = profileOffset(field); if (offset < 0) throw new UnsupportedOperationException("No item profile for this save version"); putInt(offset, value); refreshHash(); }
    private int profileOffset(ProfileField field) {
        if (gameVersion() != 150500 || bytes.length < Offsets.offsets_143) return -1;
        try {
            int offset = switch (field) {
                case NORMAL_TICKETS -> findIntOrDefault(1818501,fixed(Offsets.offsets_88))+20; case RARE_TICKETS -> findIntOrDefault(1818501,fixed(Offsets.offsets_88))+24; case PLATINUM_TICKETS -> findIntOrDefault(5100,fixed(Offsets.offsets_89))-8;
                case LEGEND_TICKETS -> legendTicketOffset(); case PLATINUM_SHARDS -> findInt(100600)-5; case NP -> findInt(80000)-5; case LEADERSHIP -> findInt(80200)-6;
            };
            return offset >= 0 && offset + 4 <= bytes.length-Offsets.offsets_130 ? offset : -1;
        } catch (RuntimeException malformedLayout) {
            return -1;
        }
    }
    private int byteAt(int offset) { if (offset < 0 || offset >= bytes.length-Offsets.offsets_130) throw new IllegalStateException("Save field is unavailable"); return bytes[offset] & 255; }
    private void putInt(int offset, int value) { bytes[offset] = (byte) value; bytes[offset + 1] = (byte) (value >> 8); bytes[offset + 2] = (byte) (value >> 16); bytes[offset + 3] = (byte) (value >> 24); }
    private int ushortAt(int offset) { return (bytes[offset]&255)|((bytes[offset+1]&255)<<8); }
    private long longAt(int offset) { long value=0; for(int i=7;i>=0;i--) value=(value<<8)|(bytes[offset+i]&255L); return (long) Double.longBitsToDouble(value); }
    private long rawLongAt(int offset) { long value=0;for(int i=7;i>=0;i--)value=(value<<8)|(bytes[offset+i]&255L);return value; }
    private void putLong(int offset,long value) { for(int i=0;i<8;i++){bytes[offset+i]=(byte)value;value>>=8;} }
    private String stringAt(int offset) { int length=intAt(offset); if(length<0||length>256||offset+4+length>bytes.length-Offsets.offsets_130)throw new IllegalStateException("Invalid save string"); return new String(bytes,offset+4,length,java.nio.charset.StandardCharsets.UTF_8); }
    private void putFixedString(int offset,String value) { if(value==null)throw new IllegalArgumentException("Missing string");byte[] encoded=value.getBytes(java.nio.charset.StandardCharsets.UTF_8);int length=intAt(offset);if(encoded.length!=length)throw new IllegalArgumentException("String length must remain "+length);System.arraycopy(encoded,0,bytes,offset+4,length); }
    private void putShort(int offset,int value) { bytes[offset]=(byte)value; bytes[offset+1]=(byte)(value>>8); }
    private int inquiryCodeOffset() {
        int end=bytes.length-Offsets.offsets_130-(region==Region.JP?26:27);
        for(int offset=Offsets.offsets_134;offset<end;offset++){
            if(rawIntAt(offset)!=9||!asciiAlphaNumeric(offset+4,9))continue;
            int hasAccount=offset+17,marker=offset+(region==Region.JP?22:23);
            if((bytes[hasAccount]&255)<=1&&(region==Region.JP||(bytes[offset+22]&255)<=1)&&rawIntAt(marker)==44)return offset;
        }
        for(int offset=Offsets.offsets_134;offset<end;offset++)if(rawIntAt(offset)==9&&asciiAlphaNumeric(offset+4,9))return offset;
        throw new IllegalStateException("Inquiry code field is unavailable");
    }
    private int accountCreatedAtOffset() { return findIntNear(60,fixed(Offsets.offsets_90),2048)-8; }
    private int passwordRefreshTokenOffset() {
        int end=bytes.length-Offsets.offsets_130-67;
        for(int offset=Math.max(0,end-100000);offset<=end;offset++){
            if(rawIntAt(offset)==40&&(bytes[offset+44]&255)<=1&&(bytes[offset+45]&255)<=1&&(bytes[offset+46]&255)<=1&&rawIntAt(offset+63)==100000)return offset;
        }
        throw new IllegalStateException("Password refresh token field is unavailable");
    }
    private int legendTicketOffset() {
        int token=passwordRefreshTokenOffset();
        for(int count=0;count<=255;count++){
            int offset=token-7-count*5;
            if(offset>=0&&(bytes[offset+4]&255)==count&&(bytes[token-2]&255)<=1&&(bytes[token-1]&255)<=1)return offset;
        }
        throw new IllegalStateException("Legend ticket field is unavailable");
    }
    private int treasureChestOffset() {
        int marker=findInt(140300),from=Math.max(0,marker-1024);
        for(int countOffset=marker-157;countOffset>=from;countOffset--){
            if((bytes[countOffset]&255)!=39)continue;
            boolean valid=true;
            for(int i=0;i<39;i++){int value=rawIntAt(countOffset+1+i*4);if(value<0||value>1000000){valid=false;break;}}
            if(valid)return countOffset+1;
        }
        throw new IllegalStateException("Treasure chest table is unavailable");
    }
    private int gamatotoHelperTableOffset() {
        int from=Math.max(4,fixed(Offsets.offsets_91)-8192),to=Math.min(bytes.length-40,fixed(Offsets.offsets_91)+8192);
        for(int marker=from;marker<=to;marker++){
            if(rawIntAt(marker)!=53)continue;
            int table=marker+4,count=rawIntAt(table);
            if(count<0||count>512)continue;
            int end=table+4+count*4;
            if(end+5<=bytes.length-Offsets.offsets_130&&(bytes[end]&255)<=1){int next=rawIntAt(end+1);if(next==54||next==56)return table;}
        }
        int legacy=fixed(Offsets.offsets_91),count=intAt(legacy);
        if(count>=0&&count<=512)return legacy;
        throw new IllegalStateException("Gamatoto helper table is unavailable");
    }
    private int baseMaterialTableOffset() {
        int estimate=fixed(Offsets.offsets_92),from=Math.max(4,estimate-8192),to=Math.min(bytes.length-40,estimate+8192);
        for(int marker=from;marker<=to;marker++){
            if(rawIntAt(marker)!=63)continue;
            int table=marker+4,count=rawIntAt(table);
            if(count<1||count>32)continue;
            int cannon=table+4+count*4+17;
            if(cannon+4<=bytes.length-Offsets.offsets_130&&rawIntAt(cannon)==8)return table;
        }
        int legacy=afterScheme(Offsets.offsets_92),count=intAt(legacy);
        if(count>=0&&count<=32)return legacy;
        throw new IllegalStateException("Base material table is unavailable");
    }
    private int talentOrbTableOffset() {
        int from=Math.max(4,fixed(Offsets.offsets_93)-16384),to=Math.min(bytes.length-40,fixed(Offsets.offsets_93)+16384);
        for(int table=from;table<=to;table++){
            int count=(bytes[table]&255)|((bytes[table+1]&255)<<8);
            if(count>2048)continue;
            int offset=table+2+count*4;
            if(offset+3>=bytes.length-Offsets.offsets_130)continue;
            int dictionaries=(bytes[offset]&255)|((bytes[offset+1]&255)<<8);offset+=2;
            if(dictionaries>2048)continue;
            boolean valid=true;
            for(int i=0;i<dictionaries;i++){
                if(offset+3>=bytes.length-Offsets.offsets_130){valid=false;break;}
                offset+=2;int inner=bytes[offset++]&255;
                if(offset+inner*3+5>bytes.length-Offsets.offsets_130){valid=false;break;}
                offset+=inner*3;
            }
            if(valid&&(bytes[offset]&255)<=1&&rawIntAt(offset+1)==90700)return table;
        }
        int legacy=afterEnigma(Offsets.offsets_93),count=ushortAt(legacy);
        if(count<=2048)return legacy;
        throw new IllegalStateException("Talent Orb table is unavailable");
    }
    private int rawIntAt(int offset) { return (bytes[offset]&255)|((bytes[offset+1]&255)<<8)|((bytes[offset+2]&255)<<16)|(bytes[offset+3]<<24); }
    private int findIntOrDefault(int value,int fallback) {try{return findInt(value);}catch(IllegalStateException missing){return fallback;}}
    private boolean asciiAlphaNumeric(int offset,int length) {if(offset<0||offset+length>bytes.length-Offsets.offsets_130)return false;for(int i=0;i<length;i++){int value=bytes[offset+i]&255;if(!((value>='0'&&value<='9')||(value>='A'&&value<='Z')||(value>='a'&&value<='z')))return false;}return true;}
    private int schemeTableOffset() { int expected=fixed(Offsets.offsets_94);try{int outbreak=currentOutbreakTableOffset();for(int delta=0;delta<=32;delta++){int[] candidates=delta==0?new int[]{expected}:new int[]{expected+delta,expected-delta};for(int table:candidates){if(table<0||table+8>outbreak)continue;int first=intAt(table);if(first<0||first>10000)continue;int secondOffset=table+4+first*4;if(secondOffset+4>outbreak)continue;int second=intAt(secondOffset);if(second>=0&&second<=10000&&secondOffset+4+second*4==outbreak)return table;}}}catch(IllegalStateException unavailable){/* Legacy fixtures predate the structural outbreak anchor. */}int first=intAt(expected),secondOffset=expected+4+first*4;if(first>=0&&first<=10000&&secondOffset+4<=bytes.length-Offsets.offsets_130){int second=intAt(secondOffset);if(second>=0&&second<=10000&&secondOffset+4+second*4<=bytes.length-Offsets.offsets_130)return expected;}throw new IllegalStateException("Scheme item table is unavailable"); }
    private int dojoScoreTableOffset() { int expected=fixed(Offsets.offsets_95);for(int delta=0;delta<=64;delta++){int[] candidates=delta==0?new int[]{expected}:new int[]{expected+delta,expected-delta};for(int table:candidates){if(table<0||table+4>bytes.length-Offsets.offsets_130)continue;int chapters=intAt(table),p=table+4;if(chapters<0||chapters>1000)continue;boolean valid=true;for(int c=0;c<chapters;c++){if(p+8>bytes.length-Offsets.offsets_130){valid=false;break;}int id=intAt(p),stages=intAt(p+4);if(id<0||id>100000||stages<0||stages>1000||p+8+stages*8>bytes.length-Offsets.offsets_130){valid=false;break;}p+=8+stages*8;}if(valid)return table;}}throw new IllegalStateException("Dojo score table is unavailable"); }
    private int dojoScoreDelta() { int table=dojoScoreTableOffset(),chapters=intAt(table),p=table+4;for(int c=0;c<chapters;c++){int stages=intAt(p+4);p+=8+stages*8;}return p-table-4; }
    private int schemeDelta() { int table=schemeTableOffset(),first=intAt(table),second=intAt(table+4+first*4);return (first+second)*4; }
    private int afterScheme(int original) { return fixed(original)+dojoScoreDelta()+schemeDelta(); }
    private void removeSchemeReceivedId(int id) { int table=schemeTableOffset(),first=intAt(table),countOffset=table+4+first*4,count=intAt(countOffset),base=countOffset+4;for(int i=0;i<count;i++)if(intAt(base+i*4)==id){splice(base+i*4,4,0);putInt(countOffset,count-1);return;} }
    private int cannonBase() {
        if(cannonBaseCache>=0&&cannonBaseCache+5<=bytes.length-Offsets.offsets_130){int count=rawIntAt(cannonBaseCache);if(count>=0&&count<=8)return cannonBaseCache;if(count<0)return cannonBaseCache;}
        int end=bytes.length-Offsets.offsets_130-9;
        for(int base=Offsets.offsets_135;base<end;base++){
            int count=rawIntAt(base);
            if(count<1||count>32||(gameVersion()>=150000&&count!=8))continue;
            int offset=base+4;
            boolean valid=true;
            for(int i=0;i<count;i++){
                if(offset+12>end||rawIntAt(offset)!=i){valid=false;break;}
                int total=rawIntAt(offset+4);
                if(total<1||total>32||offset+8+total*4>end){valid=false;break;}
                offset+=8+total*4;
            }
            if(!valid)continue;
            int selected=bytes[offset]&255;
            if(selected<=100&&(gameVersion()<=90699||selected==21)&&offset+1+selected*3<=bytes.length-Offsets.offsets_130){cannonBaseCache=base;return base;}
        }
        if(gameVersion()>=150000)for(int base=Offsets.offsets_136;base<Offsets.offsets_137&&base+Offsets.offsets_139<bytes.length-Offsets.offsets_130;base++){
            if(rawIntAt(base)!=0||byteAt(base+4)!=0)continue;
            int gold=base+Offsets.offsets_138;
            if(rawIntAt(gold)==-1&&rawIntAt(gold+4)==0){cannonBaseCache=base;return base;}
        }
        int legacy=afterScheme(Offsets.offsets_96);
        int count=rawIntAt(legacy);
        if(count>=0&&count<=100){cannonBaseCache=legacy;return legacy;}
        legacy=afterScheme(Offsets.offsets_96)+(gameVersion()>=150500?1:0);count=rawIntAt(legacy);
        if(count>=0&&count<=100){cannonBaseCache=legacy;return legacy;}
        throw new IllegalStateException("Cannon table is unavailable");
    }
    private int cannonTableLength() { int base=cannonBase(),count=intAt(base),offset=base+4;for(int i=0;i<count;i++){int total=intAt(offset+4);offset+=8+total*4;}int selected=byteAt(offset);return offset+1+selected*3-base; }
    private int cannonDelta() { return cannonTableLength()-252; }
    private int afterCannonBase(int original) { return fixed(original)+(cannonBase()-fixed(Offsets.offsets_96))+cannonDelta(); }
    private int goldPassDelta() { int count=intAt(goldPassBase()+76);if(count<0||count>10000)throw new IllegalStateException("Invalid Gold Pass rewards");return count*8; }
    private int afterCannonsWithoutRegion(int original) { return afterCannonBase(original)+(original>=Offsets.offsets_142?goldPassDelta():0); }
    private int afterCannons(int original) {
        return afterCannonsWithoutRegion(original) + regionDelta(original);
    }
    private int medalBaseOffset() { for(int base=Offsets.offsets_140;base<Math.min(bytes.length-Offsets.offsets_130,Offsets.offsets_141);base++){if(base+17>bytes.length-Offsets.offsets_130)break;int count=ushortAt(base+12);if(count>1000)continue;int dictionaryCount=base+14+count*2;if(dictionaryCount+3>bytes.length-Offsets.offsets_130)continue;int entries=ushortAt(dictionaryCount),end=dictionaryCount+2+entries*3+1;if(entries>1000||end>bytes.length-Offsets.offsets_130||byteAt(end-1)>1)continue;int gamblingLength=validGamblingTableLength(end);if(gamblingLength>=0&&end+gamblingLength+4<=bytes.length-Offsets.offsets_130&&intAt(end+gamblingLength)==90000)return base;}int legacy=afterCannons(Offsets.offsets_97),count=ushortAt(legacy+12),dictionary=legacy+14+count*2;if(count<=1000&&dictionary+3<=bytes.length-Offsets.offsets_130){int entries=ushortAt(dictionary),end=dictionary+2+entries*3+1;if(entries<=1000&&end<=bytes.length-Offsets.offsets_130&&byteAt(end-1)<=1)return legacy;}throw new IllegalStateException("Medal table is unavailable"); }
    private int medalTableLength() { int base=medalBaseOffset(),count=ushortAt(base+12),dictionary=base+14+count*2;return 17+count*2+ushortAt(dictionary)*3; }
    private int medalDelta() { return medalTableLength()-17; }
    private int afterMedals(int original) { int wildcat=medalBaseOffset()+medalTableLength();return fixed(original)+(wildcat-fixed(Offsets.offsets_98)); }
    private int wildcatDelta() { return gamblingTableLength(afterMedals(Offsets.offsets_98))-12; }
    private int afterWildcat(int original) { return afterMedals(original)+wildcatDelta(); }
    private int enigmaDelta() { int base=enigmaBaseOffset(),count=byteAt(base+11),extra=base+12+count*17;return count*17+(byteAt(extra)!=0?17:0); }
    private int afterEnigma(int original) { return fixed(original)+dojoScoreDelta()+schemeDelta()+cannonDelta()+goldPassDelta()+medalDelta()+wildcatDelta()+enigmaDelta()+regionDelta(original); }
    private int orbDelta() { return ushortAt(talentOrbTableOffset())*4; }
    private int afterOrbs(int original) { return fixed(original)+dojoScoreDelta()+schemeDelta()+cannonDelta()+goldPassDelta()+medalDelta()+wildcatDelta()+enigmaDelta()+orbDelta()+regionDelta(original); }
    private int scratcherDelta() { return gamblingTableLength(afterOrbs(Offsets.offsets_99))-6; }
    private int eventCapsules2Offset() { int marker=findInt(100400),base=marker-9;if(byteAt(base-1)!=2)throw new IllegalStateException("Event capsule table is unavailable");return base; }
    private int talentTableOffset() {
        if(talentTableBaseCache>=0)return talentTableBaseCache;
        int estimate=afterCannons(Offsets.offsets_100),from=Math.max(0,estimate-8192),to=Math.min(bytes.length-36,estimate+16384);
        for(int base=from;base<=to;base++){
            int records=rawIntAt(base);if(records<100||records>873)continue;int offset=base+4,last=-1;boolean valid=true;
            for(int r=0;r<records;r++){
                if(offset+8>bytes.length-Offsets.offsets_130){valid=false;break;}int cat=rawIntAt(offset),count=rawIntAt(offset+4);if(cat<=last||cat<0||cat>=873||count<0||count>64){valid=false;break;}last=cat;offset+=8;
                if(offset+count*8>bytes.length-Offsets.offsets_130){valid=false;break;}for(int i=0;i<count;i++){int id=rawIntAt(offset+i*8),level=rawIntAt(offset+i*8+4);if(id<0||id>1000||level<0||level>1000){valid=false;break;}}if(!valid)break;offset+=count*8;
            }
            if(valid){talentTableBaseCache=base;return base;}
        }
        throw new IllegalStateException("Cat talent table is unavailable");
    }
    private int[] talentRecord(int catId) { int table=talentTableOffset(),records=intAt(table),offset=table+4;for(int r=0;r<records;r++){int id=intAt(offset),count=intAt(offset+4);if(id==catId)return new int[]{offset+8,count};if(id>catId)break;offset+=8+count*8;}return new int[]{0,0}; }
    private int lateOffset(int original) { return fixed(original)+dojoScoreDelta()+schemeDelta()+cannonDelta()+goldPassDelta()+medalDelta()+wildcatDelta()+enigmaDelta()+orbDelta()+scratcherDelta()+regionDelta(original); }
    private int regionDelta(int original) { return region == Region.EN && original >= Offsets.offsets_1 ? 5 : 0; }
    private int fixed(int twOffset) { if(region!=Region.JP)return twOffset;if(twOffset>=Offsets.offsets_15)return twOffset-38;if(twOffset>=Offsets.offsets_7)return twOffset-37;if(twOffset>=Offsets.offsets_16)return twOffset-38;if(twOffset>=Offsets.offsets_9)return twOffset-37;if(twOffset>=Offsets.offsets_131)return twOffset-45;if(twOffset>=Offsets.offsets_17)return twOffset-41;if(twOffset>=Offsets.offsets_18)return twOffset-8;if(twOffset>=Offsets.offsets_19)return twOffset-4;if(twOffset>=Offsets.offsets_20)return twOffset-3;if(twOffset>=Offsets.offsets_21)return twOffset-2;if(twOffset>=Offsets.offsets_132)return twOffset-1;return twOffset; }
    private void splice(int offset,int remove,int insert) { if(offset<0||remove<0||insert<0||offset+remove>bytes.length-Offsets.offsets_130)throw new IllegalArgumentException("Invalid splice");byte[] out=new byte[bytes.length-remove+insert];System.arraycopy(bytes,0,out,0,offset);System.arraycopy(bytes,offset+remove,out,offset+insert,bytes.length-offset-remove);bytes=out;if(cannonBaseCache>=0&&offset<cannonBaseCache)cannonBaseCache+=insert-remove;if(goldPassBaseCache>=0&&offset<goldPassBaseCache)goldPassBaseCache+=insert-remove;if(talentTableBaseCache>=0&&offset<talentTableBaseCache)talentTableBaseCache+=insert-remove;if(storageTableBaseCache>=0&&offset<storageTableBaseCache)storageTableBaseCache+=insert-remove;if(enigmaBaseCache>=0&&offset<enigmaBaseCache)enigmaBaseCache+=insert-remove;if(eventTableBaseCache>=0&&offset<eventTableBaseCache)eventTableBaseCache+=insert-remove; }
    private void ensureItemProfile() { if (!hasItemProfile()) throw new UnsupportedOperationException("No item profile for this save version"); }
    private void ensureCatProfile() { ensureItemProfile(); }
    private int charaNewFlagsOffset() { return afterScheme(Offsets.offsets_101); }
    private void resetCharaNewFlag(int catId) { int base=charaNewFlagsOffset(),count=intAt(base),offset=base+4;if(count<0||count>10000)throw new IllegalStateException("Invalid cat flag table");for(int i=0;i<count;i++,offset+=8){if(intAt(offset)==catId){putInt(offset+4,0);return;}}splice(offset,0,8);putInt(offset,catId);putInt(offset+4,0);putInt(base,count+1); }
    private void resetAllCharaNewFlags() { int base=charaNewFlagsOffset(),count=intAt(base),offset=base+4;if(count<0||count>10000)throw new IllegalStateException("Invalid cat flag table");boolean[] present=new boolean[873];for(int i=0;i<count;i++,offset+=8){int id=intAt(offset);if(id>=0&&id<present.length){present[id]=true;putInt(offset+4,0);}}int missing=0;for(boolean found:present)if(!found)missing++;if(missing==0)return;splice(offset,0,missing*8);int write=offset;for(int id=0;id<present.length;id++)if(!present[id]){putInt(write,id);putInt(write+4,0);write+=8;}putInt(base,count+missing); }
    private void touchRankUpSale() { putInt(fixed(Offsets.offsets_43),0x7fffffff); }
    private void syncCatRankLimits(int catId) { java.util.function.IntPredicate available=id->true;int base=GameDataRules.catRankLimitBase(catId,available),plus=GameDataRules.catRankLimitPlus(catId,available);putShort(fixed(Offsets.offsets_102)+catId*4,plus);putShort(fixed(Offsets.offsets_62)+catId*4,base); }
    private void unlockCatRaw(int index) { putInt(fixed(Offsets.offsets_38)+index*4,1);putInt(fixed(Offsets.offsets_58)+index*4,1);putInt(fixed(Offsets.offsets_54),Math.max(1,intAt(fixed(Offsets.offsets_54))));for(int i=0;i<GameDataRules.dropPairCount();i++)if(GameDataRules.dropCat(i)==index)putInt(fixed(Offsets.offsets_59)+GameDataRules.dropSlot(i)*4,1); }
    private void unlockCatForUpgrade(int index) { putInt(fixed(Offsets.offsets_38)+index*4,1); putInt(fixed(Offsets.offsets_58)+index*4,1); putInt(fixed(Offsets.offsets_54),Math.max(1,intAt(fixed(Offsets.offsets_54)))); for(int i=0;i<GameDataRules.dropPairCount();i++) if(GameDataRules.dropCat(i)==index) putInt(fixed(Offsets.offsets_59)+GameDataRules.dropSlot(i)*4,1); }
    private void putIntBE(int offset,int value){bytes[offset]=(byte)(value>>>24);bytes[offset+1]=(byte)(value>>>16);bytes[offset+2]=(byte)(value>>>8);bytes[offset+3]=(byte)value;}
    private void putFormValue(int index,int value){int o=Offsets.offsets_87+index*4;bytes[o]=0;bytes[o+1]=(byte)value;bytes[o+2]=0;bytes[o+3]=0;}
    private void putFourthValue(int index,int value){int o=Offsets.offsets_103+index*4;bytes[o]=(byte)value;bytes[o+1]=0;bytes[o+2]=0;bytes[o+3]=0;}
    private void checkCat(int index) { ensureCatProfile();if(index<0||index>=873)throw new IndexOutOfBoundsException(); }
    private void checkLevel(int value) { if(value<0||value>65535)throw new IllegalArgumentException("Invalid level"); }
    private void checkAmount(String field,int value,int maximum) { if(value<0||value>maximum)throw new IllegalArgumentException(field+" must be between 0 and "+maximum); }
    private void checkUnsignedInt(long value) { if(value<0||value>0xffffffffL)throw new IllegalArgumentException("Value must fit an unsigned 32-bit integer"); }
    private void checkDisplayedLevel(int value) { if(value<1||value>65536)throw new IllegalArgumentException("Invalid level"); }
    private void checkCatLevel(int index,int value) { checkCat(index);checkLevel(value); }
    private int specialSkillUpgradeOffset(int index) { ensureCatProfile();if(index<0||index>=10)throw new IndexOutOfBoundsException();int raw=index==0?0:index+1;return fixed(Offsets.offsets_42)+raw*4; }
    private int endlessBattleOffset(int index) { if(index<0||index>=6)throw new IndexOutOfBoundsException();return afterOrbs(Offsets.offsets_104)+index*19; }
    private int storyInternalChapter(int chapter) { ensureItemProfile();if(chapter<0||chapter>=9)throw new IndexOutOfBoundsException();return chapter<3?chapter:chapter+1; }
    private void checkStoryStage(int stage) { if(stage<0||stage>=48)throw new IndexOutOfBoundsException(); }
    private int storyTreasureStorageStage(int stage) { checkStoryStage(stage);return stage>=46?stage:45-stage; }
    private int akuTableOffset() {
        int expected=lateOffset(Offsets.offsets_105),markerEstimate=expected-4;
        int best=-1,distance=Integer.MAX_VALUE;
        for(int marker=4;marker+8<bytes.length-Offsets.offsets_130;marker++)if(intAt(marker)==100700&&validAkuTable(marker+4)){int d=Math.abs(marker-markerEstimate);if(d<distance){best=marker+4;distance=d;}}
        if(best>=0)return best;
        for(int base=4;base+8<bytes.length-Offsets.offsets_130;base++)if(validAkuTableFallback(base)){int d=Math.abs(base-expected);if(d<distance){best=base;distance=d;}}
        if(best>=0)return best;
        throw new IllegalStateException("Aku data is unavailable");
    }
    private boolean validAkuTable(int base) { if(base<0||base+4>bytes.length-Offsets.offsets_130)return false;int chapters=ushortAt(base),stages=byteAt(base+2),stars=byteAt(base+3);long end=(long)base+4+(long)chapters*stars+(long)chapters*stars*stages*2;return chapters>0&&chapters<=16&&stages>0&&stages<=100&&stars>0&&stars<=16&&end<=bytes.length-Offsets.offsets_130; }
    private boolean validAkuTableFallback(int base) { if(!validAkuTable(base))return false;int chapters=ushortAt(base),stages=byteAt(base+2),stars=byteAt(base+3),tail=base+4+chapters*stars+chapters*stars*stages*2;if(tail+4>bytes.length-Offsets.offsets_130||byteAt(tail)>1||byteAt(tail+1)>1)return false;int dictionaryCount=ushortAt(tail+2);return dictionaryCount<=1024&&tail+4L+dictionaryCount*4L<=bytes.length-Offsets.offsets_130; }
    private int akuStageOffset(int chapter,int star,int stage) { ensureItemProfile();int base=akuTableOffset(),chapters=ushortAt(base),stages=byteAt(base+2),stars=byteAt(base+3);if(chapters>100||stages>100||stars>16||chapter<0||chapter>=chapters||star<0||star>=stars||stage<0||stage>=stages)throw new IndexOutOfBoundsException();return base+4+chapters*stars+((chapter*stars+star)*stages+stage)*2; }
    private int missionDictionaryOffset(int dictionary) { ensureItemProfile();if(dictionary<0||dictionary>=8)throw new IndexOutOfBoundsException();int offset=afterCannons(Offsets.offsets_69);for(int d=0;d<dictionary;d++){int count=intAt(offset);if(count<0||count>10000)throw new IllegalStateException("Invalid mission dictionary");offset+=4+count*8;}return offset; }
    private int eventCompletionDictionaryOffset() { int expected=fixed(Offsets.offsets_106);for(int delta=0;delta<=512;delta++){int[] candidates=delta==0?new int[]{expected}:new int[]{expected+delta,expected-delta};for(int offset:candidates){if(offset<0||offset+4>bytes.length-Offsets.offsets_130)continue;int count=intAt(offset);if(count<0||count>1000||offset+4+count*8>bytes.length-Offsets.offsets_130)continue;boolean valid=true,hasEventKey=count==0;for(int i=0;i<count;i++){int key=intAt(offset+4+i*8),value=intAt(offset+8+i*8);if(key<0||key>100000||value<0){valid=false;break;}hasEventKey|=key>=1000;}if(valid&&hasEventKey)return offset;}}throw new IllegalStateException("Event completion dictionary is unavailable"); }
    private int[] dictionaryKeys(int offset) { int count=intAt(offset);if(count<0||count>10000)throw new IllegalStateException("Invalid dictionary");int[] keys=new int[count];for(int i=0;i<count;i++)keys[i]=intAt(offset+4+i*8);return keys; }
    private int dictionaryValue(int offset,int key) { int count=intAt(offset);for(int i=0;i<count;i++)if(intAt(offset+4+i*8)==key)return intAt(offset+8+i*8);throw new IllegalArgumentException("Unknown key"); }
    private boolean dictionaryContains(int offset,int key) { int count=intAt(offset);for(int i=0;i<count;i++)if(intAt(offset+4+i*8)==key)return true;return false; }
    private void upsertDictionaryValue(int offset,int key,int value) { int count=intAt(offset);for(int i=0;i<count;i++)if(intAt(offset+4+i*8)==key){putInt(offset+8+i*8,value);return;}int end=offset+4+count*8;splice(end,0,8);putInt(offset,count+1);putInt(end,key);putInt(end+4,value); }
    private void setDictionaryValue(int offset,int key,int value) { int count=intAt(offset);for(int i=0;i<count;i++)if(intAt(offset+4+i*8)==key){putInt(offset+8+i*8,value);return;}throw new IllegalArgumentException("Unknown key"); }
    private void checkMap(MapLayout l,int map,int star,int stage,boolean checkStage) { if(map<0||map>=l.maps||star<0||star>=l.starsAt(map)||(checkStage&&(stage<0||stage>=l.stagesAt(map,star))))throw new IndexOutOfBoundsException(); }
    private void checkVisibleMap(StageMap type,MapLayout l,int map,int star,int stage,boolean checkStage) { checkMap(l,map,star,stage,checkStage);if(star>=configuredCrownCount(type,map))throw new IndexOutOfBoundsException(); }
    private int configuredCrownCount(StageMap type,int map) {
        return switch(type){
            case EVENT -> {int group=map/500,id=map%500;if(group==0)yield id>=1&&id<=48?1:0;if(group==1)yield id<EVENT_MAP_CROWNS.length()?EVENT_MAP_CROWNS.charAt(id)-'0':0;if(group==2)yield id<COLLAB_MAP_CROWNS.length()?COLLAB_MAP_CROWNS.charAt(id)-'0':0;yield 0;}
            case UNCANNY -> map<49?4:0;
            case CATAMIN -> map<52?1:0;
            case LEGEND_QUEST -> map==0?1:0;
            case TOWER -> map<12?1:0;
            case ZERO_LEGENDS -> map<23?1:map<34?2:0;
            case GAUNTLETS -> map<82?1:0;
            case ENIGMA_CLEARS -> map<73?1:0;
            case COLLAB_GAUNTLETS -> map<28?1:0;
            case BEHEMOTH -> map<3?1:0;
            case DOJO -> map<12?1:0;
            case CHALLENGE -> 1;
        };
    }
    private int[] cannonEntry(int index) { int count=cannonCount();if(index<0||index>=count)throw new IndexOutOfBoundsException();int offset=cannonBase()+4;for(int i=0;i<count;i++){int id=intAt(offset),total=intAt(offset+4);if(total<1||total>32)throw new IllegalStateException("Invalid cannon");if(i==index)return new int[]{id,offset,total-1};offset+=8+total*4;}throw new IndexOutOfBoundsException(); }
    private int fixedArrayValue(int offset,int count,int index) { ensureItemProfile();if(index<0||index>=count)throw new IndexOutOfBoundsException();return intAt(offset+index*4); }
    private void setFixedArrayValue(int offset,int count,int index,int value) { ensureItemProfile();if(index<0||index>=count)throw new IndexOutOfBoundsException();putInt(offset+index*4,value);refreshHash(); }
    private void checkLineup(int lineup,int slot) { ensureItemProfile();if(lineup<0||lineup>=lineupCount()||slot<0||slot>=10)throw new IndexOutOfBoundsException(); }
    private int itfTimedScoreOffset(int chapter,int stage) { if(chapter<0||chapter>=3||stage<0||stage>=48)throw new IndexOutOfBoundsException();return itfTimedScoreBase()+((chapter*51)+stage)*4; }
    private int itfTimedScoreBase() { int marker=findIntNear(44,fixed(Offsets.offsets_107),64);int base=marker+8;if(base+3*51*4>bytes.length-Offsets.offsets_130)throw new IllegalStateException("ITF timed scores are unavailable");return base; }
    private int catShrineBase() { int marker=findInt(90900),xp=marker-24;for(int count=32;count>=1;count--){int length=xp-count-1,base=length-18;if(base>=0&&byteAt(length)==count&&byteAt(base)<=1&&byteAt(base+17)<=1)return base;}int length=xp-1,base=length-18;if(base>=0&&byteAt(length)==0&&byteAt(base)<=1&&byteAt(base+17)<=1)return base;throw new IllegalStateException("Cat Shrine is unavailable"); }
    private int catShrineXpOffset() { return findInt(90900)-24; }
    private int catShrineDialogsOffset() { return findInt(110700)+4; }
    private int storageTableOffset() { if(storageTableBaseCache>=0)return storageTableBaseCache;int expected=fixed(Offsets.offsets_108);for(int delta=0;delta<=64;delta++){int[] candidates=delta==0?new int[]{expected}:new int[]{expected+delta,expected-delta};for(int table:candidates){if(table<0||table+2>bytes.length-Offsets.offsets_130)continue;int count=ushortAt(table);if(count<64||count>256||table+2+count*8>bytes.length-Offsets.offsets_130)continue;boolean valid=true,hasItem=false;for(int i=0;i<count;i++){int id=intAt(table+2+i*4),type=intAt(table+2+count*4+i*4);if(id<0||id>100000||type<0||type>3){valid=false;break;}hasItem|=type!=0;}if(valid&&(hasItem||table==expected)){storageTableBaseCache=table;return table;}}}throw new IllegalStateException("Storage table is unavailable"); }
    private int enigmaStageOffset(int index) { int count=enigmaStageCount();if(index<0||index>=count)throw new IndexOutOfBoundsException();return enigmaBaseOffset()+12+index*17; }
    private int enigmaBaseOffset() { if(enigmaBaseCache>=0)return enigmaBaseCache;int expected=afterWildcat(Offsets.offsets_109);for(int delta=0;delta<=96;delta++){int[] candidates=delta==0?new int[]{expected}:new int[]{expected+delta,expected-delta};for(int base:candidates){if(base<0||base+13>bytes.length-Offsets.offsets_130)continue;int energy1=intAt(base),energy2=intAt(base+4),level=byteAt(base+8),unknown=byteAt(base+9),flag=byteAt(base+10),count=byteAt(base+11);if(energy1<0||energy2<0||level>100||unknown!=1||flag>1||count>73)continue;int p=base+12;boolean valid=true;for(int i=0;i<count;i++){if(p+17>bytes.length-Offsets.offsets_130){valid=false;break;}int stageLevel=intAt(p),id=intAt(p+4),decoding=byteAt(p+8);if(stageLevel<0||stageLevel>1000||id<25000||id>=25073||decoding>2){valid=false;break;}p+=17;}if(valid&&p<bytes.length-Offsets.offsets_130&&byteAt(p)<=1){enigmaBaseCache=base;return base;}}}if(expected>=0&&expected+13<=bytes.length-Offsets.offsets_130){enigmaBaseCache=expected;return expected;}throw new IllegalStateException("Enigma table is unavailable"); }
    private void checkStorage(int slot) { ensureItemProfile();if(slot<0||slot>=storageCount())throw new IndexOutOfBoundsException(); }
    private void addStorageItem(int type,int id) { int slot=firstEmptyStorageSlot();if(slot<0)throw new IllegalStateException("Storage is full");setStorageItem(slot,type,id); }
    private void ensureStorageSpace(int needed) { if(storageCount()-occupiedStorageCount()<needed)throw new IllegalStateException("Storage is full"); }
    private void setCannonDevelopmentRaw(int index,int value) { int[] e=cannonEntry(index);putInt(e[1]+8,value); }
    private int goldPassBase(){if(goldPassBaseCache>=0&&goldPassBaseCache+90<=bytes.length-Offsets.offsets_130)return goldPassBaseCache;int expected=afterCannonBase(Offsets.offsets_110);for(int delta=0;delta<=512;delta++){int[] candidates=delta==0?new int[]{expected}:new int[]{expected+delta,expected-delta};for(int base:candidates){if(base<0||base+90>bytes.length-Offsets.offsets_130)continue;int officer=intAt(base),renewals=intAt(base+4),count=intAt(base+76);if((officer!=-1&&officer<=0)||renewals<0||renewals>10000||count<0||count>10000)continue;int tail=base+80+count*8;if(tail+10<=bytes.length-Offsets.offsets_130&&byteAt(tail+8)<=1&&byteAt(tail+9)<=1){goldPassBaseCache=base;return base;}}}if(expected>=0&&expected+90<=bytes.length-Offsets.offsets_130&&intAt(expected)==0&&intAt(expected+4)==0&&intAt(expected+76)==0){goldPassBaseCache=expected;return expected;}throw new IllegalStateException("Gold Pass table is unavailable");}
    private void clearGoldPassClaims() { int countOffset=goldPassBase()+76,count=intAt(countOffset);if(count<0||count>10000)throw new IllegalStateException("Invalid Gold Pass rewards");if(count>0)splice(countOffset+4,count*8,0);putInt(countOffset,0); }
    private void putDouble(int offset,long value) { putLong(offset,Double.doubleToRawLongBits((double)value)); }
    private int gamblingTableOffset(GamblingTable type) { ensureItemProfile();return type==GamblingTable.WILDCAT_SLOTS?medalBaseOffset()+medalTableLength():afterOrbs(Offsets.offsets_99); }
    private int gamblingStartTableOffset(GamblingTable type) { int offset=gamblingTableOffset(type),count=ushortAt(offset);offset+=2+count*3;count=ushortAt(offset);offset+=2;for(int i=0;i<count;i++){offset+=2;int inner=ushortAt(offset);offset+=2+inner*4;}return offset; }
    private int gamblingTableLength(int base) { int offset=base,count=ushortAt(offset);if(count>1000)throw new IllegalStateException("Invalid gambling table");offset+=2+count*3;count=ushortAt(offset);if(count>1000)throw new IllegalStateException("Invalid gambling table");offset+=2;for(int i=0;i<count;i++){offset+=2;int inner=ushortAt(offset);if(inner>1000)throw new IllegalStateException("Invalid gambling table");offset+=2+inner*4;}count=ushortAt(offset);if(count>1000)throw new IllegalStateException("Invalid gambling table");offset+=2+count*6;return offset-base; }
    private int validGamblingTableLength(int base) { try{int length=gamblingTableLength(base);return base+length<=bytes.length-Offsets.offsets_130?length:-1;}catch(RuntimeException invalid){return -1;} }
    private int[] outbreakChapter(int index) { int table=outbreakTableOffset(),count=intAt(table);if(index<0||index>=count)throw new IndexOutOfBoundsException();int offset=table+4;for(int i=0;i<count;i++){int id=intAt(offset),stages=intAt(offset+4);if(i==index)return new int[]{id,offset,stages};offset+=8+stages*5;}throw new IndexOutOfBoundsException(); }
    private void clearCurrentOutbreak(int chapterId,int stageId) { int table=currentOutbreakTableOffset(),chapters=intAt(table),offset=table+4;for(int chapter=0;chapter<chapters;chapter++){int id=intAt(offset),stages=intAt(offset+4);offset+=8;for(int stage=0;stage<stages;stage++){if(id==chapterId&&intAt(offset)==stageId)bytes[offset+4]=0;offset+=5;}} }
    private int outbreakTableOffset() { return findOutbreakTable(fixed(Offsets.offsets_111),true); }
    private int currentOutbreakTableOffset() { return findOutbreakTable(fixed(Offsets.offsets_112),false); }
    private int findOutbreakTable(int expected,boolean fullTable) { for(int delta=0;delta<=32;delta++){int[] candidates=delta==0?new int[]{expected}:new int[]{expected+delta,expected-delta};for(int candidate:candidates)if(validOutbreakTable(candidate,fullTable))return candidate;}throw new IllegalStateException("Outbreak table is unavailable"); }
    private boolean validOutbreakTable(int table,boolean fullTable) { if(table<0||table+4>bytes.length-Offsets.offsets_130)return false;int chapters=intAt(table);if(chapters<=0||chapters>32)return false;int offset=table+4,previous=-1,totalStages=0;for(int chapter=0;chapter<chapters;chapter++){if(offset+8>bytes.length-Offsets.offsets_130)return false;int id=intAt(offset),stages=intAt(offset+4);if(id<0||id>1000||id<=previous||stages<=0||stages>100){return false;}previous=id;offset+=8;for(int stage=0;stage<stages;stage++){if(offset+5>bytes.length-Offsets.offsets_130)return false;int stageId=intAt(offset),state=byteAt(offset+4);if(stageId<0||stageId>1000||state>1)return false;if(fullTable&&stageId!=stage)return false;offset+=5;}totalStages+=stages;}return fullTable?totalStages>=chapters*40:totalStages>=chapters; }
    private MapLayout mapLayout(StageMap type) { ensureItemProfile();return switch(type){
        case CHALLENGE -> standardLayout(challengeTableOffset(),true);
        case UNCANNY -> standardLayout(standardTableOffset(afterCannons(Offsets.offsets_113),49),false);
        case CATAMIN -> standardLayout(standardTableOffset(afterCannons(Offsets.offsets_114),52),false);
        case TOWER -> standardLayout(afterCannons(Offsets.offsets_115),true);
        case GAUNTLETS -> compactLayout(compactTableOffset(afterWildcat(Offsets.offsets_116),82));
        case ENIGMA_CLEARS -> compactLayout(findInt(90300)+4);
        case COLLAB_GAUNTLETS -> compactLayout(compactTableOffset(afterEnigma(Offsets.offsets_117),28));
        case EVENT -> eventLayout(eventTableOffset());
        case BEHEMOTH -> compactLayout(findInt(110000)+4);
        case LEGEND_QUEST -> legendQuestLayout(legendQuestTableOffset());
        case ZERO_LEGENDS -> variableLayout(findInt(111000)+4);
        case DOJO -> variableLayout(variableTableOffset(lateOffset(Offsets.offsets_118),12));
    }; }
    private int compactTableOffset(int expected,int expectedMaps){for(int delta=0;delta<=10000;delta++){int[] candidates=delta==0?new int[]{expected}:new int[]{expected+delta,expected-delta};for(int base:candidates){if(base<0||base+4>bytes.length-Offsets.offsets_130||ushortAt(base)!=expectedMaps)continue;int stages=byteAt(base+2),stars=byteAt(base+3);long end=(long)base+4+(long)expectedMaps*stars*2+(long)expectedMaps*stages*stars*2;if(stages>0&&stages<=100&&stars>0&&stars<=16&&end<=bytes.length-Offsets.offsets_130)return base;}}throw new IllegalStateException("Compact map table is unavailable");}
    private int eventTableOffset(){if(validEventTable(eventTableBaseCache))return eventTableBaseCache;int expected=fixed(Offsets.offsets_119),best=-1,distance=Integer.MAX_VALUE;for(int base=0;base+5<=bytes.length-Offsets.offsets_130;base++){if(!validEventTable(base))continue;int d=Math.abs(base-expected);if(d<distance){best=base;distance=d;}}if(best>=0){eventTableBaseCache=best;return best;}throw new IllegalStateException("Event map table is unavailable");}
    private boolean validEventTable(int base){if(base<0||base+5>bytes.length-Offsets.offsets_130)return false;int types=byteAt(base),subchapters=ushortAt(base+1),stars=byteAt(base+3),stages=byteAt(base+4);long maps=(long)types*subchapters,end=(long)base+5+maps*stars*2+maps*stages*stars*2;return types>=2&&types<=16&&subchapters>268&&subchapters<=2000&&maps==2500&&stars>0&&stars<=16&&stages>0&&stages<=100&&end<=bytes.length-Offsets.offsets_130;}
    private int standardTableOffset(int expected,int expectedMaps){for(int delta=0;delta<=10000;delta++){int[] candidates=delta==0?new int[]{expected}:new int[]{expected+delta,expected-delta};for(int base:candidates){if(base<0||base+12>bytes.length-Offsets.offsets_130||intAt(base)!=expectedMaps)continue;int stages=intAt(base+4),stars=intAt(base+8);long end=(long)base+12+(long)expectedMaps*stars*8+(long)expectedMaps*stages*stars*4;if(stages>0&&stages<=100&&stars>0&&stars<=16&&end<=bytes.length-Offsets.offsets_130)return base;}}throw new IllegalStateException("Standard map table is unavailable");}
    private int legendQuestTableOffset(){int expected=afterCannons(Offsets.offsets_120);for(int delta=0;delta<=512;delta++){int[] candidates=delta==0?new int[]{expected}:new int[]{expected+delta,expected-delta};for(int base:candidates){if(base<0||base+3>bytes.length-Offsets.offsets_130||byteAt(base)!=1)continue;int stages=byteAt(base+1),stars=byteAt(base+2);long end=(long)base+3+stars*3L+stages*stars*4L;if(stages>0&&stages<=100&&stars>0&&stars<=16&&end<=bytes.length-Offsets.offsets_130)return base;}}throw new IllegalStateException("Legend Quest table is unavailable");}
    private int variableTableOffset(int expected,int expectedMaps){for(int delta=0;delta<=10000;delta++){int[] candidates=delta==0?new int[]{expected}:new int[]{expected+delta,expected-delta};for(int base:candidates){if(base<0||base+2>bytes.length-Offsets.offsets_130||ushortAt(base)!=expectedMaps)continue;int p=base+2;boolean valid=true;for(int map=0;map<expectedMaps&&valid;map++){if(p+2>bytes.length-Offsets.offsets_130){valid=false;break;}p++;int stars=byteAt(p++);if(stars<1||stars>16){valid=false;break;}for(int star=0;star<stars;star++){if(p+5>bytes.length-Offsets.offsets_130){valid=false;break;}p+=3;int stages=ushortAt(p);p+=2;if(stages<1||stages>1000||p+stages*2>bytes.length-Offsets.offsets_130){valid=false;break;}p+=stages*2;}}if(valid)return base;}}throw new IllegalStateException("Variable map table is unavailable");}
    private int challengeTableOffset(){int expected=afterCannons(Offsets.offsets_121);for(int delta=0;delta<=128;delta++){int[] candidates=delta==0?new int[]{expected}:new int[]{expected+delta,expected-delta};for(int base:candidates){if(base<0||base+44>bytes.length-Offsets.offsets_130||intAt(base)!=1)continue;int stars=intAt(base+4);if(stars<1||stars>16)continue;int p=base+8+stars*4+8+stars*4,stages=intAt(p+4);long end=(long)p+12+(long)stages*stars*4+8+(long)stars*4;if(stages>0&&stages<=100&&end<=bytes.length-Offsets.offsets_130)return base;}}throw new IllegalStateException("Challenge map table is unavailable");}
    private MapLayout standardLayout(int base,boolean repeated){MapLayout l=new MapLayout();l.valueWidth=4;l.maps=intAt(base);l.stars=intAt(base+4);if(l.maps<0||l.maps>1000||l.stars<0||l.stars>16)throw new IllegalStateException("Invalid map data");int p=base+8+l.maps*l.stars*4;if(repeated)p+=8;l.progressBase=p;p+=l.maps*l.stars*4;if(repeated){l.stages=intAt(p+4);p+=12;}else{l.stages=intAt(base+4);l.stars=intAt(base+8);l.progressBase=base+12+l.maps*l.stars*4;p=l.progressBase+l.maps*l.stars*4;}l.stageBase=p;l.stageMajor=true;l.unlockBase=l.stageBase+l.maps*l.stages*l.stars*4+(repeated?8:0);l.unlockValueWidth=4;return l;}
    private MapLayout compactLayout(int base){MapLayout l=new MapLayout();l.maps=ushortAt(base);l.stages=byteAt(base+2);l.stars=byteAt(base+3);if(l.maps<0||l.maps>1000||l.stages<0||l.stages>100||l.stars<0||l.stars>16)throw new IllegalStateException("Invalid compact map data");l.progressBase=base+4+l.maps*l.stars;l.stageBase=l.progressBase+l.maps*l.stars;l.unlockBase=l.stageBase+l.maps*l.stages*l.stars*2;l.shortValues=true;l.stageMajor=true;return l;}
    private MapLayout variableLayout(int base){MapLayout l=new MapLayout();l.maps=ushortAt(base);if(l.maps<0||l.maps>1000)throw new IllegalStateException("Invalid variable map data");l.variable=true;l.shortValues=true;l.variableStageOffsets=new int[l.maps][];l.variableProgressOffsets=new int[l.maps][];int p=base+2;for(int map=0;map<l.maps;map++){p++;int starCount=byteAt(p++);l.variableStageOffsets[map]=new int[starCount];l.variableProgressOffsets[map]=new int[starCount];for(int star=0;star<starCount;star++){p++;l.variableProgressOffsets[map][star]=p++;p++;int count=ushortAt(p);p+=2;l.variableStageOffsets[map][star]=p;p+=count*2;}}return l;}
    private MapLayout eventLayout(int base){MapLayout l=new MapLayout();int types=byteAt(base),subchapters=ushortAt(base+1);l.maps=types*subchapters;l.mapGroupSize=subchapters;l.stars=byteAt(base+3);l.stages=byteAt(base+4);l.progressBase=base+5+l.maps*l.stars;l.stageBase=l.progressBase+l.maps*l.stars;l.unlockBase=l.stageBase+l.maps*l.stages*l.stars*2;l.shortValues=true;l.stageMajor=true;l.valueWidth=2;l.cascadeCompletion=true;return l;}
    private void clearEventMap(int type,int map,int star) { int base=eventTableOffset(),types=byteAt(base),subchapters=ushortAt(base+1),stars=byteAt(base+3),stages=byteAt(base+4);if(type<0||type>=types||map<0||map>=subchapters||star<0||star>=stars)throw new IndexOutOfBoundsException();int flat=type*subchapters+map,progress=base+5+types*subchapters*stars,stageBase=progress+types*subchapters*stars,unlockBase=stageBase+types*subchapters*stages*stars*2;bytes[progress+flat*stars+star]=(byte)stages;for(int stage=0;stage<stages;stage++)putShort(stageBase+((flat*stages+stage)*stars+star)*2,1);bytes[unlockBase+flat*stars+star]=3;if(star+1<stars)bytes[unlockBase+flat*stars+star+1]=1;if(map+1<subchapters)bytes[unlockBase+(flat+1)*stars]=1; }
    private MapLayout legendQuestLayout(int base){MapLayout l=new MapLayout();l.maps=byteAt(base);l.stages=byteAt(base+1);l.stars=byteAt(base+2);l.progressBase=base+3+l.maps*l.stars;l.stageBase=l.progressBase+l.maps*l.stars;l.mirrorStageBase=l.stageBase+l.maps*l.stages*l.stars*2;l.unlockBase=l.mirrorStageBase+l.maps*l.stages*l.stars*2;l.shortValues=true;l.stageMajor=true;l.valueWidth=2;return l;}
    private final class MapLayout {
        int maps,stars,stages,stageBase,progressBase,valueWidth=2,mirrorStageBase=-1,unlockBase=-1,unlockValueWidth=1,mapGroupSize;boolean shortValues,variable,stageMajor,cascadeCompletion;
        int[][] variableStageOffsets;int[][] variableProgressOffsets;
        int starsAt(int map){return variable?variableStageOffsets[map].length:stars;}
        int stagesAt(int map,int star){return variable?(variableProgressOffsets[map][star]+3<bytes.length?ushortAt(variableProgressOffsets[map][star]+2):0):stages;}
        int stageOffset(int map,int star,int stage){if(variable)return variableStageOffsets[map][star]+stage*2;if(stageMajor)return stageBase+((map*stages+stage)*stars+star)*valueWidth;return stageBase+((map*stars+star)*stages+stage)*2;}
        int mirrorOffset(int map,int star,int stage){return mirrorStageBase+((map*stages+stage)*stars+star)*2;}
        void updateProgress(int map,int star,int stage,int value){if(value>0){int current=currentProgress(map,star);if(current<stage+1)setProgress(map,star,stage+1);setUnlock(map,star,3);if(cascadeCompletion&&stage==stagesAt(map,star)-1){if(star+1<starsAt(map))setUnlock(map,star+1,1);else if(hasNextMap(map))setUnlock(map+1,0,1);}}else{setProgress(map,star,Math.min(stage,currentProgress(map,star)));for(int s=star+1;s<starsAt(map);s++)setUnlock(map,s,0);if(star==0&&hasNextMap(map))for(int s=0;s<starsAt(map+1);s++)setUnlock(map+1,s,0);}}
        int currentProgress(int map,int star){return variable?byteAt(variableProgressOffsets[map][star]):shortValues?byteAt(progressBase+map*stars+star):intAt(progressBase+(map*stars+star)*4);}
        void setProgress(int map,int star,int value){if(variable)bytes[variableProgressOffsets[map][star]]=(byte)value;else if(shortValues)bytes[progressBase+map*stars+star]=(byte)value;else putInt(progressBase+(map*stars+star)*4,value);}
        void setUnlock(int map,int star,int value){if(unlockBase<0)return;int offset=unlockBase+(map*stars+star)*unlockValueWidth;if(unlockValueWidth==1)bytes[offset]=(byte)value;else putInt(offset,value);}
        boolean hasNextMap(int map){return map+1<maps&&(mapGroupSize==0||(map+1)%mapGroupSize!=0);}
    }
    private int[] intArray(int offset,int count) { ensureItemProfile(); int[] out=new int[count]; for(int i=0;i<count;i++) out[i]=intAt(offset+i*4); return out; }
    private void setArrayInt(int offset,int count,int index,int value) { ensureItemProfile(); if(index<0||index>=count)throw new IndexOutOfBoundsException(); putInt(offset+index*4,value); refreshHash(); }
    private static String md5(String salt, byte[] bytes, int offset, int length) { return hex(md5Bytes(salt, bytes, offset, length)); }
    private static byte[] md5Bytes(String salt, byte[] bytes) { return md5Bytes(salt, bytes, 0, bytes.length); }
    private static byte[] md5Bytes(String salt, byte[] bytes, int offset, int length) {
        try { MessageDigest md = MessageDigest.getInstance("MD5"); md.update(salt.getBytes(java.nio.charset.StandardCharsets.UTF_8)); md.update(bytes, offset, length); return md.digest(); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(bytes.length * 2); for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b)); return out.toString(); }
}
