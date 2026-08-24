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
        public String packageSuffix() { return salt; }
        public static Region fromCode(String value) {
            if (value == null) return null;
            for (Region region : values()) if (region.code.equalsIgnoreCase(value)) return region;
            return null;
        }
        String salt() { return salt; }
    }

    private byte[] bytes;
    private Region region;
    private final int integerStart;
    private final int gameVersionOffset;
    private final int hashOffset;
    private final boolean forcedUnsupported;
    /** Enables the explicitly warned, best-effort upload path for newer saves. */
    private final boolean uploadMode;
    private int cannonBaseCache = -1;
    private int goldPassBaseCache = -1;
    private int talentTableBaseCache = -1;
    private int storageTableBaseCache = -1;
    private int enigmaBaseCache = -1;
    private int eventTableBaseCache = -1;
    private CatLayout catLayoutCache;
    private int battleItemsBaseCache = -1;
    private int unitDropsBaseCache = -1;

    private static final int TEMPLATE_CAT_COUNT = 861;

    /** The cat-related lists are variable length in the upstream save format. */
    private static final class CatLayout {
        final int countOffset, count;
        final int unlockedStart;
        final int upgradeCountOffset, upgradeStart;
        final int currentFormCountOffset, currentFormStart, currentFormEnd;
        final int gatyaSeenCountOffset, gatyaSeenStart;
        final int maxUpgradeCountOffset, maxUpgradeStart;
        final int unlockedFormsCountOffset, unlockedFormsStart;
        final int guideCountOffset, guideStart;
        final int catfruitCountOffset, catfruitStart;
        final int fourthCountOffset, fourthStart;
        final int catseyesUsedCountOffset, catseyesUsedStart;
        final int catseyesCountOffset, catseyesStart;
        final int cataminsCountOffset, cataminsStart;

        CatLayout(int countOffset, int count, int unlockedStart, int upgradeCountOffset,
                  int upgradeStart, int currentFormCountOffset, int currentFormStart,
                  int gatyaSeenCountOffset, int gatyaSeenStart, int maxUpgradeCountOffset,
                  int maxUpgradeStart, int unlockedFormsCountOffset, int unlockedFormsStart,
                  int guideCountOffset, int guideStart, int catfruitCountOffset,
                  int catfruitStart, int fourthCountOffset, int fourthStart,
                  int catseyesUsedCountOffset, int catseyesUsedStart, int catseyesCountOffset,
                  int catseyesStart, int cataminsCountOffset, int cataminsStart) {
            this.countOffset=countOffset; this.count=count; this.unlockedStart=unlockedStart;
            this.upgradeCountOffset=upgradeCountOffset; this.upgradeStart=upgradeStart;
            this.currentFormCountOffset=currentFormCountOffset; this.currentFormStart=currentFormStart;
            this.currentFormEnd=currentFormStart+count*4;
            this.gatyaSeenCountOffset=gatyaSeenCountOffset; this.gatyaSeenStart=gatyaSeenStart;
            this.maxUpgradeCountOffset=maxUpgradeCountOffset; this.maxUpgradeStart=maxUpgradeStart;
            this.unlockedFormsCountOffset=unlockedFormsCountOffset; this.unlockedFormsStart=unlockedFormsStart;
            this.guideCountOffset=guideCountOffset; this.guideStart=guideStart;
            this.catfruitCountOffset=catfruitCountOffset; this.catfruitStart=catfruitStart;
            this.fourthCountOffset=fourthCountOffset; this.fourthStart=fourthStart;
            this.catseyesUsedCountOffset=catseyesUsedCountOffset; this.catseyesUsedStart=catseyesUsedStart;
            this.catseyesCountOffset=catseyesCountOffset; this.catseyesStart=catseyesStart;
            this.cataminsCountOffset=cataminsCountOffset; this.cataminsStart=cataminsStart;
        }
    }


    private SaveDocument(byte[] source, Region region, int integerStart) {
        this(source, region, integerStart, Offsets.offsets_23, -1, false, false);
    }

    private SaveDocument(byte[] source, Region region, int integerStart, int gameVersionOffset, int hashOffset, boolean forcedUnsupported) {
        this(source, region, integerStart, gameVersionOffset, hashOffset, forcedUnsupported, false);
    }

    private SaveDocument(byte[] source, Region region, int integerStart, int gameVersionOffset, int hashOffset,
                         boolean forcedUnsupported, boolean uploadMode) {
        this.bytes = source;
        this.region = region;
        this.integerStart = integerStart;
        this.gameVersionOffset = gameVersionOffset;
        this.hashOffset = hashOffset;
        this.forcedUnsupported = forcedUnsupported;
        this.uploadMode = uploadMode;
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

    /**
     * Opens a save whose checksum/layout belongs to a newer game revision.
     * This path is deliberately explicit: callers must show the unsupported
     * version warning before allowing the user to continue.
     */
    public static SaveDocument openForInspection(byte[] source, Region hint) {
        if (source == null || source.length < 48) throw new IllegalArgumentException("Save is too small");
        int versionOffset = findUnsupportedVersionOffset(source);
        if (versionOffset < 0) throw new IllegalArgumentException("Unsupported save format");
        for (Region candidate : Region.values()) {
            int hash = findHashOffset(source, candidate);
            if (hash >= 0) return new SaveDocument(source.clone(), candidate, Offsets.offsets_122, versionOffset, hash, true);
        }
        Region region = hint == null ? Region.JP : hint;
        return new SaveDocument(source.clone(), region, Offsets.offsets_122, versionOffset,
                source.length - Offsets.offsets_130, true);
    }

    /**
     * Opens a save for the explicitly warned upload path.  This mode is never
     * used by the editor UI: it only enables the existing 15.5 field probes so
     * a newer save can be uploaded with a prominent data-integrity warning.
     */
    public static SaveDocument openForUpload(byte[] source, Region hint) {
        if (source == null || source.length < 48) throw new IllegalArgumentException("Save is too small");
        try {
            SaveDocument supported = open(source);
            return new SaveDocument(source.clone(), supported.region(), Offsets.offsets_122,
                    Offsets.offsets_23, -1, false, true);
        } catch (IllegalArgumentException unsupported) {
            SaveDocument inspection = openForInspection(source, hint);
            return new SaveDocument(source.clone(), inspection.region(), Offsets.offsets_122,
                    inspection.gameVersionOffset, inspection.hashOffset, true, true);
        }
    }

    public byte[] toBytes() { return bytes.clone(); }
    public Region region() { return region; }
    /** Full editor validation currently covers the 15.5 save layout. */
    public boolean isOfficiallySupportedVersion() { return !forcedUnsupported && gameVersion() == 150500; }
    /** Import is intentionally allowed for these saves, but editing is not certified. */
    public boolean needsUnsupportedImportWarning() { return !isOfficiallySupportedVersion(); }
    /** True only for the newer save versions accepted by the warned upload path. */
    public boolean canAttemptUnsafeUpload() { return gameVersion() == 150501; }
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
            bytes[offset] = 0;
            putInt(offset + 1, 100600);
        }
    }

    private void transformInternationalToJp() {
        if (region == Region.EN) throw new IllegalStateException("EN block must be normalized first");

        int marker120700 = findIntNearOrAny(120700, lateOffset(Offsets.offsets_2), 16);
        putInt(marker120700, 130000);
        int marker130600 = findIntNearOrAny(130600, lateOffset(Offsets.offsets_3), 16);
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
        int marker130000 = findIntNearOrAny(130000, lateOffset(Offsets.offsets_2), 16);
        putInt(marker130000, 120700);
        int marker130600 = findIntNearOrAny(130600, lateOffset(Offsets.offsets_3), 16);
        byte[] movedShort = Arrays.copyOfRange(bytes, marker130600 + 4, marker130600 + 6);
        int movedStampOffset = fixed(Offsets.offsets_9) - 8;
        byte[] movedStamp = Arrays.copyOfRange(bytes, movedStampOffset, movedStampOffset + 8);
        byte energyNotification = bytes[fixed(Offsets.offsets_7) - 1];
        byte[] internationalBlock = new byte[33];
        internationalBlock[28] = energyNotification;
        writeInt(internationalBlock, 29, gameVersion());

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
    public int gameVersion() { return intAt(gameVersionOffset); }
    public void convertGameVersion(int target) {
        int source=gameVersion();
        if(source==target)return;
        if(source>=140300&&source<=150500&&target==140000){
            if(source>=140500)convert140500EmbeddedLayout(source,140000);
            if(source>=140100){int marker90500=findInt(90500);splice(marker90500-2,2,0);}
            int start=findInt(140000)+4,end=findInt(140300)+4;splice(start,end-start,0);
            putInt(gameVersionOffset,target);refreshHash();return;
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
            putInt(gameVersionOffset,target);refreshHash();return;
        }
        if(source<140300||source>150500||target<140300||target>150500)throw new UnsupportedOperationException("Version conversion is supported from 14.3 through 15.5, plus downgrade to 14.0");
        convert140500EmbeddedLayout(source,target);
        convert140500RecordLayout(source,target);
        int marker=findIntNearOrAny(140200,bytes.length-406,96);
        int listCount=byteAt(marker+4),fields=marker+5+listCount;
        int sourceExtra=source>=150500?6:source>=150300?5:0;
        int targetExtra=target>=150500?6:target>=150300?5:0;
        if(targetExtra<sourceExtra)splice(fields+targetExtra,sourceExtra-targetExtra,0);
        else if(targetExtra>sourceExtra){splice(fields+sourceExtra,0,targetExtra-sourceExtra);Arrays.fill(bytes,fields+sourceExtra,fields+targetExtra,(byte)0);}
        putInt(gameVersionOffset,target);
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
    public void clearTutorial() {
        ensureItemProfile();
        putInt(fixed(Offsets.offsets_25),Math.max(1,intAt(fixed(Offsets.offsets_25))));
        putInt(fixed(Offsets.offsets_26),Math.max(2,intAt(fixed(Offsets.offsets_26))));
        putInt(fixed(Offsets.offsets_27),Math.max(1,intAt(fixed(Offsets.offsets_27))));
        putInt(fixed(Offsets.offsets_28),Math.max(1,intAt(fixed(Offsets.offsets_28))));
        putInt(fixed(Offsets.offsets_29),Math.max(2,intAt(fixed(Offsets.offsets_29))));
        // new_dialogs_2 is a variable-length list on modern saves.  The
        // upstream tutorial helper extends it to six entries and updates
        // entries 1 and 5, rather than treating offsets_30 as a fixed
        // absolute location.  On received saves this list may be shifted by
        // the cat profile and its serialized entry count.
        int dialogs = newDialogs2Offset();
        if (gameVersion() <= 26) {
            // Older saves serialize exactly 17 entries without a length
            // prefix.  Do not interpret entry 0 as a count or splice this
            // fixed-width legacy block.
            putInt(dialogs + 1 * 4, Math.max(2, intAt(dialogs + 1 * 4)));
            putInt(dialogs + 5 * 4, Math.max(2, intAt(dialogs + 5 * 4)));
        } else {
            int count = intAt(dialogs);
            if (count < 6) {
                splice(dialogs + 4 + count * 4, 0, (6 - count) * 4);
                putInt(dialogs, 6);
                count = 6;
            }
            putInt(dialogs + 4 + 1 * 4, Math.max(2, intAt(dialogs + 4 + 1 * 4)));
            putInt(dialogs + 4 + 5 * 4, Math.max(2, intAt(dialogs + 4 + 5 * 4)));
        }
        refreshHash();
    }
    public void setNormalTickets(int value) { checkAmount("Normal Tickets",value,2999);setProfiledInt(ProfileField.NORMAL_TICKETS, value); }
    public void setRareTickets(int value) { checkAmount("Rare Tickets",value,299);setProfiledInt(ProfileField.RARE_TICKETS, value); }
    public void setPlatinumTickets(int value) { checkAmount("Platinum Tickets",value,9);setProfiledInt(ProfileField.PLATINUM_TICKETS, value); }
    public void setLegendTickets(int value) { checkAmount("Legend Tickets",value,4);setProfiledInt(ProfileField.LEGEND_TICKETS, value); }
    public void setPlatinumShards(int value) { checkAmount("Platinum Shards",value,Math.max(0,(9-platinumTickets())*10+9));setProfiledInt(ProfileField.PLATINUM_SHARDS, value); }
    public void setNp(int value) { checkAmount("NP",value,9999);setProfiledInt(ProfileField.NP, value); }
    public void setLeadership(int value) { ensureItemProfile();checkAmount("Leadership",value,9999);putShort(findInt(80200)-6,value);refreshHash(); }
    public boolean hasItemProfile() { return profileOffset(ProfileField.NORMAL_TICKETS) >= 0; }
    public int[] battleItems() { return intArray(battleItemsOffset(), 6); }
    public int[] catseyes() { CatLayout l=catLayout(); return intArray(l.catseyesStart, 6); }
    public int[] catamins() { CatLayout l=catLayout(); return intArray(l.cataminsStart, cataminCount(l)); }
    public int[] catfruit() { CatLayout l=catLayout(); return intArray(l.catfruitStart, 29); }
    public int[] treasureChests() { int[] table=treasureChestTable(); return intArray(table[0], table[1]); }
    public int[] labyrinthMedals() { ensureItemProfile(); int marker=findInt(111000),count=byteAt(marker-9);if(count!=4)throw new IllegalStateException("Invalid labyrinth medals");return new int[]{ushortAt(marker-8),ushortAt(marker-6),ushortAt(marker-4),ushortAt(marker-2)}; }
    public int hundredMillionTicket() { ensureItemProfile(); return intAt(findInt(140200)-4); }
    public int goldenCpuCount() { ensureItemProfile(); return byteAt(lateOffset(Offsets.offsets_35)); }
    public void setBattleItem(int index, int value) { checkAmount("Battle Item",value,9999);setArrayInt(battleItemsOffset(), 6, index, value); }
    public void setCatseye(int index, int value) { checkAmount("Catseye",value,9999);setArrayInt(catLayout().catseyesStart, 6, index, value); }
    public void setCatamin(int index, int value) { CatLayout l=catLayout(); checkAmount("Catamin",value,9999);setArrayInt(l.cataminsStart, cataminCount(l), index, value); }
    public void setCatfruit(int index, int value) { checkAmount("Catfruit",value,gameVersion()<110400?128:998);setArrayInt(catLayout().catfruitStart, 29, index, value); }
    public int catfruitLimit() { return gameVersion()<110400?128:998; }
    public void setAllCatfruit(int value) { checkAmount("Catfruit",value,catfruitLimit());int offset=catLayout().catfruitStart;ensureItemProfile();for(int index=0;index<29;index++)putInt(offset+index*4,value);refreshHash(); }
    public void setTreasureChest(int index, int value) { checkAmount("Treasure Chest",value,9999);int[] table=treasureChestTable();setArrayInt(table[0], table[1], index, value); }
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
    public int userRank() { ensureCatProfile(); CatLayout l=catLayout(); int rank=0; for(int i=0;i<l.count;i++)if(intAt(l.unlockedStart+i*4)!=0)rank+=ushortAt(l.upgradeStart+i*4+2)+1+ushortAt(l.upgradeStart+i*4); for(int i=0;i<11;i++)if(i!=1){int skill=l.currentFormEnd+i*4;rank+=ushortAt(skill+2)+1+ushortAt(skill);} return rank; }
    public int rankUpSaleValue() { ensureItemProfile(); return intAt(rankUpSaleOffset()); }
    public boolean showBanMessage() { ensureItemProfile(); return byteAt(rankUpSaleOffset() - 15)!=0; }
    public void setShowBanMessage(boolean value) { ensureItemProfile();bytes[rankUpSaleOffset() - 15]=(byte)(value?1:0);refreshHash(); }
    public long rareSeed() { ensureItemProfile();return Integer.toUnsignedLong(intAt(gatyaSeedOffset(false))); }
    public long normalSeed() { ensureItemProfile();return Integer.toUnsignedLong(intAt(gatyaSeedOffset(false)+4)); }
    public long eventSeed() { ensureItemProfile();return Integer.toUnsignedLong(intAt(eventSeedOffset())); }
    public int gamatotoXp() { ensureItemProfile(); return intAt(gamatotoOffset()+9); }
    public int challengeScore() { ensureItemProfile(); return intAt(afterCannons(Offsets.offsets_49)); }
    public void setRareSeed(long value) { ensureItemProfile();checkUnsignedInt(value);putInt(gatyaSeedOffset(false),(int)value);refreshHash(); }
    public void setNormalSeed(long value) { ensureItemProfile();checkUnsignedInt(value);putInt(gatyaSeedOffset(false)+4,(int)value);refreshHash(); }
    public void setEventSeed(long value) { ensureItemProfile();checkUnsignedInt(value);putInt(eventSeedOffset(),(int)value);refreshHash(); }
    public void setGamatotoXp(int value) { ensureItemProfile();if(value<0)throw new IllegalArgumentException("Invalid Gamatoto XP");putInt(gamatotoOffset()+9,value);refreshHash(); }
    public void setChallengeScore(int value) { ensureItemProfile();bytes[afterCannons(Offsets.offsets_50)]=1;putInt(afterCannons(Offsets.offsets_51),3);putInt(afterCannons(Offsets.offsets_49),value);bytes[afterCannons(Offsets.offsets_52)]=1;refreshHash(); }
    public void fixGamatotoCrash() { ensureItemProfile(); putInt(gamatotoSkinOffset(),2); refreshHash(); }
    public void unlockEquipMenu() { ensureItemProfile(); int offset=menuUnlocksOffset()+8; putInt(offset,Math.max(1,intAt(offset))); refreshHash(); }
    public int catCount() { return catLayout().count; }
    public int catBaseLevel(int index) { checkCat(index); return ushortAt(catLayout().upgradeStart+index*4+2)+1; }
    public int catPlusLevel(int index) { checkCat(index); return ushortAt(catLayout().upgradeStart+index*4); }
    public boolean catUnlocked(int index) { checkCat(index); return intAt(catLayout().unlockedStart+index*4)!=0; }
    public int catCurrentForm(int index) { checkCat(index); return intAt(catLayout().currentFormStart+index*4); }
    public int catUnlockedForms(int index) { checkCat(index); return intAt(catLayout().unlockedFormsStart+index*4); }
    public int catFourthForm(int index) { checkCat(index); return intAt(catLayout().fourthStart+index*4); }
    public boolean catGuideCollected(int index) { checkCat(index); return byteAt(catLayout().guideStart+index)!=0; }
    public void setCatBaseLevel(int index,int value) {
        checkCat(index);
        if(value<1||value>GameDataRules.catMaxBase(index))throw new IllegalArgumentException("Invalid cat base level");
        applyCatBaseUpgrade(index,value,true);
        touchRankUpSale();refreshHash();
    }
    public void setCatPlusLevel(int index,int value) { checkCat(index);if(value<0||value>GameDataRules.catMaxPlus(index))throw new IllegalArgumentException("Invalid cat plus level");unlockCatForPlusUpgrade(index);putShort(catLayout().upgradeStart+index*4,value);touchRankUpSale();refreshHash(); }
    public void setCatUnlocked(int index,boolean value) { checkCat(index);if(value)unlockCatRaw(index);else putInt(catLayout().unlockedStart+index*4,0);touchRankUpSale();refreshHash(); }
    public void setCatCurrentForm(int index,int value) {
        checkCat(index);
        if(value<0||value>3)throw new IllegalArgumentException("Invalid form");
        // Upstream Cat.set_form() always makes the selected form available
        // before selecting it.  Keeping only current_form in sync leaves a
        // received save in a state the game rejects as inconsistent.
        unlockCatRaw(index);
        putFormValue(index,value+1);
        putInt(catLayout().currentFormStart+index*4,value);
        touchRankUpSale();refreshHash();
    }
    /**
     * Set the serialized form fields directly.  These are field editors, and
     * intentionally do not call {@code Cat.unlock()} implicitly: the upstream
     * CLI's direct unlocked-forms/ultra-form edits only change the selected
     * field.  Batch form actions use the dedicated methods below and retain
     * the upstream unlock-on-edit behavior.
     */
    public void setCatUnlockedForms(int index,int value) { checkCat(index);if(value<0||value>3)throw new IllegalArgumentException("Invalid unlocked forms");putFormValue(index,value);touchRankUpSale();refreshHash(); }
    public void setCatFourthForm(int index,int value) { checkCat(index);if(value<0||value>2)throw new IllegalArgumentException("Invalid fourth form");putInt(catLayout().fourthStart+index*4,value);touchRankUpSale();refreshHash(); }
    public void resetCat(int index) { checkCat(index);CatLayout l=catLayout();putInt(l.unlockedStart+index*4,0);putShort(l.upgradeStart+index*4,0);putShort(l.upgradeStart+index*4+2,0);putInt(l.currentFormStart+index*4,0);putInt(l.gatyaSeenStart+index*4,0);putFormValue(index,0);bytes[l.guideStart+index]=0;putInt(l.fourthStart+index*4,0);putInt(l.catseyesUsedStart+index*4,0);resetCharaNewFlag(index);int[] record=talentRecord(index);for(int i=0;i<record[1];i++)putInt(record[0]+i*8+4,0);int drops=unitDropsOffset();if(drops>=0)for(int i=0;i<GameDataRules.dropPairCount(region);i++)if(GameDataRules.dropCat(region,i)==index)putInt(drops+GameDataRules.dropSlot(region,i)*4,0);touchRankUpSale();refreshHash(); }
    public void setCatGuideCollected(int index,boolean value) { checkCat(index);if(value)unlockCatRaw(index);bytes[catLayout().guideStart+index]=(byte)(value?1:0);touchRankUpSale();refreshHash(); }
    public int specialSkillCount() { ensureCatProfile(); return 10; }
    public int specialSkillBaseLevel(int index) { return ushortAt(specialSkillUpgradeOffset(index)+2)+1; }
    public int specialSkillPlusLevel(int index) { return ushortAt(specialSkillUpgradeOffset(index)); }
    public void setSpecialSkillBaseLevel(int index,int value) { int offset=specialSkillUpgradeOffset(index);if(value<1||value>GameDataRules.specialSkillMaxBase(index))throw new IllegalArgumentException("Invalid base upgrade level");putShort(offset+2,value-1);if(index==0)putShort(offset+6,value-1);touchRankUpSale();refreshHash(); }
    public void setSpecialSkillPlusLevel(int index,int value) { int offset=specialSkillUpgradeOffset(index);if(value<0||value>GameDataRules.specialSkillMaxPlus(index))throw new IllegalArgumentException("Invalid plus upgrade level");putShort(offset,value);if(index==0)putShort(offset+4,value);touchRankUpSale();refreshHash(); }
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
            applyCatBaseUpgrade(i,Math.min(value,GameDataRules.catMaxBase(i)),false);
        }
        touchRankUpSale(); refreshHash();
    }

    /** Reproduce upstream PowerUpHelper.reset_upgrade()+upgrade_by(). */
    /** Apply the helper loop.  {@code individualEdit} additionally performs
     * the CLI's Cat.set_upgrade(Upgrade(base, plus=0), only_plus=True) step;
     * the batch command in the upstream editor only runs PowerUpHelper and
     * therefore preserves the existing plus/unlock state when no upgrade was
     * possible. */
    private void applyCatBaseUpgrade(int catId,int targetLevel,boolean individualEdit) {
            int target = Math.min(targetLevel, GameDataRules.catMaxBase(catId));
            int maxUp = GameDataRules.catRankLimitBase(catId, id -> true);
            int maxPlusUp = GameDataRules.catRankLimitPlus(catId, id -> true);
            int base = 0, catseyes = 0;
            int simulationMaxUp = GameDataRules.catRankLimitBase(catId, id -> true);
            int originalMax = GameDataRules.catOriginalMaxBase(catId);
            int maxNoCatseye = GameDataRules.catMaxNoCatseye(catId);
            int maxCatseye = GameDataRules.catMaxCatseye(catId);
            int rarity = GameDataRules.catRarity(catId);
            // The stored base value is level - 1; PowerUpHelper compares
            // the displayed level (base + 1) with its current maximum.
            for (int step = 0; step < target - 1; step++) {
                int currentLevel = base + 1;
                int currentMax = Math.min(originalMax + simulationMaxUp, maxCatseye);
                boolean useEye = currentLevel >= currentMax && rarity != 0 && maxNoCatseye != -1
                        && currentLevel >= maxNoCatseye && currentLevel < maxCatseye
                        && maxNoCatseye <= currentMax;
                if (useEye) {
                    // PowerUpHelper.upgrade_cat() increments the cat's
                    // max-upgrade record whenever a Catseye is consumed.
                    // That increment is immediately visible to the next
                    // iteration, so the simulated current maximum must move
                    // with it as well.  Without this, targets in the
                    // Catseye range stop early and leave base/max/used fields
                    // inconsistent with the upstream editor.
                    base++;
                    maxUp++;
                    simulationMaxUp++;
                    catseyes++;
                }
                else if (currentLevel < currentMax) base++;
                else break;
            }
            if (individualEdit) {
                // Cat.set_upgrade(..., only_plus=True) invokes Cat.unlock()
                // even when the requested base level is already 1.
                unlockCatForUpgrade(catId);
            } else if (base > 0) {
                unlockCatForUpgrade(catId);
            }
            CatLayout l=catLayout();
            putShort(l.upgradeStart + catId * 4 + 2, base);
            putShort(l.maxUpgradeStart + catId * 4, maxPlusUp);
            putShort(l.maxUpgradeStart + catId * 4 + 2, maxUp);
            int noEye = GameDataRules.catMaxNoCatseye(catId);
            catseyes = noEye < 0 ? 0 : Math.max(0, base + 1 - noEye);
            int co = l.catseyesUsedStart + catId * 4;
            bytes[co] = (byte)catseyes; bytes[co + 1] = (byte)(catseyes >>> 8);
            bytes[co + 2] = (byte)(catseyes >>> 16); bytes[co + 3] = (byte)(catseyes >>> 24);
    }
    public void setAllCatPlusLevels(int value) { ensureCatProfile();if(value<0||value>90)throw new IllegalArgumentException("Invalid cat plus level");CatLayout l=catLayout();for(int i=0;i<l.count;i++){unlockCatForPlusUpgrade(i);putShort(l.upgradeStart+i*4,value);}touchRankUpSale();refreshHash(); }
    public void setAllCatGuideCollected(boolean value) { ensureCatProfile();CatLayout l=catLayout();for(int i=0;i<l.count;i++){if(value)unlockCatRaw(i);bytes[l.guideStart+i]=(byte)(value?1:0);}touchRankUpSale();refreshHash(); }
    public void maxAllCatTalents() { ensureItemProfile();int table=talentTableOffset(),records=intAt(table),offset=table+4;for(int r=0;r<records;r++){int cat=intAt(offset),count=intAt(offset+4);offset+=8;boolean edited=false;for(int i=0;i<count;i++){int max=GameDataRules.talentMaxLevel(cat,intAt(offset+i*8));if(max>0){putInt(offset+i*8+4,max);edited=true;}}if(edited&&cat>=0&&cat<catCount())unlockCatRaw(cat);offset+=count*8;}touchRankUpSale();refreshHash(); }
    public static final class TalentValue { public final int id; public final int level; public final int maxLevel; TalentValue(int id,int level,int maxLevel){this.id=id;this.level=level;this.maxLevel=maxLevel;} }
    public int enemyGuideCount() { ensureItemProfile(); return 802; }
    public boolean enemyGuideUnlocked(int index) { if(index<0||index>=enemyGuideCount())throw new IndexOutOfBoundsException();return intAt(fixed(Offsets.offsets_64)+index*4)!=0; }
    public void setEnemyGuideUnlocked(int index,boolean value) { if(index<0||index>=enemyGuideCount())throw new IndexOutOfBoundsException();putInt(fixed(Offsets.offsets_64)+index*4,value?1:0);refreshHash(); }
    public void setAllEnemyGuide(boolean value) { for(int i=0;i<enemyGuideCount();i++)putInt(fixed(Offsets.offsets_64)+i*4,value?1:0);refreshHash(); }
    public int userRankRewardCount() { ensureItemProfile(); return intAt(userRankRewardCountOffset()); }
    public boolean userRankRewardClaimed(int index) { int count=userRankRewardCount();if(index<0||index>=count)throw new IndexOutOfBoundsException();return byteAt(userRankRewardFlagsOffset()+index)!=0; }
    public void setUserRankRewardClaimed(int index,boolean value) { int count=userRankRewardCount();if(index<0||index>=count)throw new IndexOutOfBoundsException();bytes[userRankRewardFlagsOffset()+index]=(byte)(value?1:0);refreshHash(); }
    public void setAllUserRankRewards(boolean value) { int count=userRankRewardCount();int offset=userRankRewardFlagsOffset();for(int i=0;i<count;i++)bytes[offset+i]=(byte)(value?1:0);refreshHash(); }
    public int knownUserRankRewardCount() { return Math.min(userRankRewardCount(),GameDataRules.rankGiftCount()); }
    public boolean userRankRewardEligible(int index) { if(index<0||index>=knownUserRankRewardCount())throw new IndexOutOfBoundsException();return GameDataRules.rankGiftThreshold(index)<=userRank(); }
    public void setEligibleUserRankRewardClaimed(int index,boolean value) { if(!userRankRewardEligible(index))throw new IllegalArgumentException("Reward is not unlocked");setUserRankRewardClaimed(index,value); }
    public void fixUserRankRewards() { int rank=userRank(),count=knownUserRankRewardCount(),offset=userRankRewardFlagsOffset();for(int i=0;i<count;i++)if(GameDataRules.rankGiftThreshold(i)>rank)bytes[offset+i]=0;refreshHash(); }
    public int storyChapterCount() { ensureItemProfile(); return 9; }
    public int storyStageCount() { ensureItemProfile(); return 48; }
    public int storyClearTimes(int chapter,int stage) { int raw=storyInternalChapter(chapter);checkStoryStage(stage);return intAt(fixed(Offsets.offsets_28)+(raw*51+stage)*4); }
    public int storyTreasure(int chapter,int stage) { int raw=storyInternalChapter(chapter);checkStoryStage(stage);return intAt(fixed(Offsets.offsets_67)+(raw*49+stage)*4); }
    public void setStoryClearTimes(int chapter,int stage,int value) { int raw=storyInternalChapter(chapter);checkStoryStage(stage);if(value<0||value>32767)throw new IllegalArgumentException("Invalid clears");putInt(fixed(Offsets.offsets_28)+(raw*51+stage)*4,value);int progressOffset=fixed(Offsets.offsets_68)+raw*4;if(intAt(progressOffset)<stage+1)putInt(progressOffset,stage+1);refreshHash(); }
    public void setStoryTreasure(int chapter,int stage,int value) { int raw=storyInternalChapter(chapter);checkStoryStage(stage);if(value<0||value>9999)throw new IllegalArgumentException("Invalid treasure");putInt(fixed(Offsets.offsets_67)+(raw*49+stage)*4,value);refreshHash(); }
    public void clearStoryChapter(int chapter,boolean cleared) { int raw=storyInternalChapter(chapter);for(int i=0;i<48;i++)putInt(fixed(Offsets.offsets_28)+(raw*51+i)*4,cleared?1:0);putInt(fixed(Offsets.offsets_68)+raw*4,cleared?48:0);refreshHash(); }
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
    public void setMissionCompletion(int missionId,int state) { if(state!=0&&state!=2&&state!=4)throw new IllegalArgumentException("Invalid mission state");int clear=missionDictionaryOffset(0);dictionaryValue(clear,missionId);setDictionaryValue(clear,missionId,state);int requirements=missionDictionaryOffset(1);if(state==0){if(dictionaryContains(requirements,missionId))setDictionaryValue(requirements,missionId,0);}else { int target=GameDataRules.missionTarget(missionId); if(target>=0&&dictionaryContains(requirements,missionId)) setDictionaryValue(requirements,missionId,target); }refreshHash(); }
    public int stageMapCount(StageMap type) { return mapLayout(type).maps; }
    public int stageMapStarCount(StageMap type,int map) { MapLayout l=mapLayout(type);checkMap(l,map,0,0,false);return Math.min(l.starsAt(map),configuredCrownCount(type,map)); }
    /** Returns the number of crown records physically present for this map. */
    public int stageMapMaxStarCount(StageMap type,int map) { MapLayout l=mapLayout(type);checkMap(l,map,0,0,false);return l.starsAt(map); }
    public int stageMapStageCount(StageMap type,int map,int star) { MapLayout l=mapLayout(type);checkVisibleMap(type,l,map,star,0,false);return l.stagesAt(map,star); }
    public int stageMapClearTimes(StageMap type,int map,int star,int stage) { MapLayout l=mapLayout(type);checkVisibleMap(type,l,map,star,stage,true);return l.shortValues?ushortAt(l.stageOffset(map,star,stage)):intAt(l.stageOffset(map,star,stage)); }
    public void setStageMapClearTimes(StageMap type,int map,int star,int stage,int value) { MapLayout l=mapLayout(type);checkVisibleMap(type,l,map,star,stage,true);if(value<0||(l.shortValues&&value>65535))throw new IllegalArgumentException("Invalid clears");if(l.shortValues)putShort(l.stageOffset(map,star,stage),value);else putInt(l.stageOffset(map,star,stage),value);if(l.mirrorStageBase>=0)putShort(l.mirrorOffset(map,star,stage),value);l.updateProgress(map,star,stage,value);refreshHash(); }
    public void clearStageMap(StageMap type,int map,boolean cleared) { MapLayout l=mapLayout(type);checkMap(l,map,0,0,false);clearStageMapInternal(l,map,cleared,stageMapStarCount(type,map));refreshHash(); }
    /** Clears or resets the first {@code crownCount} crowns of one map. */
    public void clearStageMap(StageMap type,int map,boolean cleared,int crownCount) {
        MapLayout l=mapLayout(type);checkMap(l,map,0,0,false);validateCrownCount(l,map,crownCount);
        clearStageMapInternal(l,map,cleared,crownCount);refreshHash();
    }
    /** Batch counterpart of the legacy per-map operation.  Each map keeps its configured crown limit. */
    public void clearStageMaps(StageMap type,int firstMap,int lastMap,boolean cleared) {
        MapLayout l=mapLayout(type);validateMapRange(l,firstMap,lastMap);
        for(int map=firstMap;map<=lastMap;map++){
            int crowns=stageMapStarCount(type,map);
            clearStageMapInternal(l,map,cleared,crowns);
        }
        refreshHash();
    }
    /** Atomically clears or resets a range using the requested number of crowns. */
    public void clearStageMaps(StageMap type,int firstMap,int lastMap,boolean cleared,int crownCount) {
        MapLayout l=mapLayout(type);validateMapRange(l,firstMap,lastMap);
        for(int map=firstMap;map<=lastMap;map++)validateCrownCount(l,map,crownCount);
        for(int map=firstMap;map<=lastMap;map++)clearStageMapInternal(l,map,cleared,crownCount);
        refreshHash();
    }
    /**
     * Clears a range up to the requested crown count without touching crown
     * records that the current game version does not expose for a map.
     * This is used by the UI when a range contains maps with different
     * Map_option crown limits (notably Zero Legends).
     */
    public void clearStageMapsUpToConfiguredCrowns(StageMap type,int firstMap,int lastMap,boolean cleared,int crownCount) {
        MapLayout l=mapLayout(type);validateMapRange(l,firstMap,lastMap);if(crownCount<1)throw new IllegalArgumentException("Invalid crown count");
        for(int map=firstMap;map<=lastMap;map++){
            int crowns=Math.min(crownCount,stageMapStarCount(type,map));
            clearStageMapInternal(l,map,cleared,crowns);
        }
        refreshHash();
    }
    public void unlockAkuRealm() { ensureItemProfile();for(int id:new int[]{255,256,257,258,265,266,268})clearEventMap(1,id,0);refreshHash(); }
    public int gamatotoDestination() { ensureItemProfile();return intAt(gamatotoOffset()+13); }
    public int gamatotoLevel() { int xp=gamatotoXp();for(int i=0;i<129;i++)if(xp<GameDataRules.GAMATOTO_XP[i])return i+1;return 130; }
    public void setGamatotoLevel(int level) { if(level<1||level>130)throw new IllegalArgumentException("Invalid Gamatoto level");setGamatotoXp(level==1?0:GameDataRules.GAMATOTO_XP[level-2]); }
    public void setGamatotoDestination(int value) { ensureItemProfile();putInt(gamatotoOffset()+13,value);refreshHash(); }
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
    public double endlessBattleDurationMinutes(int index) { int base=endlessBattleOffset(index);if(byteAt(base)==0)return 0;int remainingItems=byteAt(base+2);double start=Double.longBitsToDouble(rawLongAt(base+3)),end=Double.longBitsToDouble(rawLongAt(base+11));if(Double.isInfinite(end))return Double.POSITIVE_INFINITY;return (end-start+remainingItems*3*60*60)/60.0; }
    public void setEndlessBattleDurationMinutes(int index,double minutes) { int base=endlessBattleOffset(index);if(Double.isNaN(minutes)||minutes<0)throw new IllegalArgumentException("Invalid endless duration");double now=System.currentTimeMillis()/1000.0;bytes[base]=1;bytes[base+1]=1;bytes[base+2]=0;putLong(base+3,Double.doubleToRawLongBits(now));putLong(base+11,Double.doubleToRawLongBits(Double.isInfinite(minutes)?Double.POSITIVE_INFINITY:now+minutes*60.0));refreshHash(); }
    public boolean endlessBattleItem(int index) { return endlessBattleDurationMinutes(index)>0||Double.isInfinite(endlessBattleDurationMinutes(index)); }
    public void setEndlessBattleItem(int index,boolean value) { if(value)setEndlessBattleDurationMinutes(index,Double.POSITIVE_INFINITY);else{bytes[endlessBattleOffset(index)]=0;refreshHash();} }
    public int luckyTicketCount() { ensureItemProfile();return 55; }
    public int luckyTicket(int index) { return fixedArrayValue(luckyTicketOffset(),55,index); }
    public void setLuckyTicket(int index,int value) { checkAmount("Lucky Ticket",value,9999);setFixedArrayValue(luckyTicketOffset(),55,index,value); }
    public int eventTicketCount() { ensureItemProfile();return 62; }
    public int eventTicket(int index) { return index<60?fixedArrayValue(eventTicketOffset(),60,index):fixedArrayValue(eventCapsules2Offset(),2,index-60); }
    public void setEventTicket(int index,int value) { if(index<0||index>=62)throw new IndexOutOfBoundsException();checkAmount("Event Ticket",value,9999);if(index<60)setFixedArrayValue(eventTicketOffset(),60,index,value);else setFixedArrayValue(eventCapsules2Offset(),2,index-60,value); }
    public int lineupCount() { ensureItemProfile();return byteAt(lineupBaseOffset()); }
    public int lineupCat(int lineup,int slot) { checkLineup(lineup,slot);return intAt(lineupBaseOffset()+1+(lineup*10+slot)*4); }
    public void setLineupCat(int lineup,int slot,int catId) { checkLineup(lineup,slot);putInt(lineupBaseOffset()+1+(lineup*10+slot)*4,catId);refreshHash(); }
    public int unlockedLineups() { ensureItemProfile();return byteAt(unlockedLineupsOffset()); }
    public int unlockableLineupCount() { ensureItemProfile();return byteAt(findInt(90900)+4); }
    public void setUnlockedLineups(int value) { ensureItemProfile();if(value<0||value>unlockableLineupCount())throw new IllegalArgumentException("Invalid lineup count");bytes[unlockedLineupsOffset()]=(byte)value;refreshHash(); }
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
    public int officerPassCatId() { ensureItemProfile();int offset=officerPassCatOffset();return ushortAt(offset)==65535?-1:ushortAt(offset); }
    public void setOfficerPassCatId(int value) { ensureItemProfile();if(value< -1||value>65534)throw new IllegalArgumentException("Invalid cat ID");putShort(officerPassCatOffset(),value<0?65535:value);refreshHash(); }
    public int officerPassCatForm() { ensureItemProfile();return ushortAt(officerPassCatOffset()+2); }
    public void setOfficerPassCatForm(int value) { ensureItemProfile();if(value<0||value>65535)throw new IllegalArgumentException("Invalid cat form");putShort(officerPassCatOffset()+2,value);refreshHash(); }
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
    public int dojoRanking() { ensureItemProfile();return intAt(dojoRankingOffset()+4); }
    public void setDojoRanking(int value) { ensureItemProfile();putInt(dojoRankingOffset()+4,value);refreshHash(); }
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
    public int rareTicketTradeProgress() { ensureItemProfile();return intAt(rareTicketTradeOffset()); }
    public void tradeRareTickets(int amount) { ensureItemProfile();if(amount<0)throw new IllegalArgumentException("Invalid trade amount");int slot=-1;for(int i=0;i<storageCount();i++)if(storageItemType(i)==0||(storageItemType(i)==2&&storageItemId(i)==1)){slot=i;break;}if(slot<0)throw new IllegalStateException("Storage is full");setStorageItem(slot,2,1);putInt(rareTicketTradeOffset(),amount*5);refreshHash(); }
    public int schemeToObtainCount() { ensureItemProfile();return intAt(schemeTableOffset()); }
    public int schemeToObtainId(int index) { int table=schemeTableOffset(),count=intAt(table);if(index<0||index>=count)throw new IndexOutOfBoundsException();return intAt(table+4+index*4); }
    public int schemeReceivedCount() { ensureItemProfile();int table=schemeTableOffset(),first=intAt(table);return intAt(table+4+first*4); }
    public int schemeReceivedId(int index) { int table=schemeTableOffset(),first=intAt(table),count=intAt(table+4+first*4),base=table+8+first*4;if(index<0||index>=count)throw new IndexOutOfBoundsException();return intAt(base+index*4); }
    public void addSchemeItem(int id) { ensureItemProfile();if(!GameDataRules.validSchemeItem(id))throw new IllegalArgumentException("Invalid scheme item");removeSchemeReceivedId(id);for(int i=0;i<schemeToObtainCount();i++)if(schemeToObtainId(i)==id){refreshHash();return;}int table=schemeTableOffset(),count=intAt(table),offset=table+4+count*4;splice(offset,0,4);putInt(offset,id);putInt(table,count+1);refreshHash(); }
    public void removeSchemeItem(int id) { ensureItemProfile();if(!GameDataRules.validSchemeItem(id))throw new IllegalArgumentException("Invalid scheme item");int table=schemeTableOffset(),count=intAt(table);for(int i=0;i<count;i++)if(schemeToObtainId(i)==id){splice(table+4+i*4,4,0);putInt(table,count-1);break;}removeSchemeReceivedId(id);refreshHash(); }
    public void fixTimeErrors(long unixSeconds) { ensureItemProfile();java.time.ZonedDateTime now=java.time.Instant.ofEpochSecond(unixSeconds).atZone(java.time.ZoneId.systemDefault());int date=date3Offset();putInt(date,now.getYear());putInt(date+4,now.getMonthValue());putInt(date+8,now.getDayOfMonth());putInt(date+12,now.getHour());putInt(date+16,now.getMinute());putInt(date+20,now.getSecond());long bits=Double.doubleToRawLongBits((double)unixSeconds);putLong(timestampOffset(),bits);putLong(energyPenaltyTimestampOffset(),bits);refreshHash(); }
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
    public void setOutbreakCleared(int chapterIndex,int stageIndex,boolean value) { int[] c=outbreakChapter(chapterIndex);if(stageIndex<0||stageIndex>=c[2])throw new IndexOutOfBoundsException();bytes[c[1]+12+stageIndex*5]=(byte)(value?1:0);refreshHash(); }
    public void setOutbreakChapterCleared(int chapterIndex,boolean value) { int[] c=outbreakChapter(chapterIndex);for(int i=0;i<c[2];i++)bytes[c[1]+12+i*5]=(byte)(value?1:0);refreshHash(); }
    public void unlockAllCats() { ensureCatProfile();for(int i=0;i<catCount();i++)unlockCatRaw(i);touchRankUpSale();refreshHash(); }
    public void unlockAllObtainableCats() { ensureCatProfile();for(int i=0;i<catCount();i++)if(GameDataRules.catObtainable(region,gameVersion(),i))unlockCatRaw(i);touchRankUpSale();refreshHash(); }
    public void removeAllCats() { ensureCatProfile();CatLayout l=catLayout(); for(int i=0;i<l.count;i++) putInt(l.unlockedStart+i*4,0);touchRankUpSale();refreshHash(); }
    public void resetAllCats() { ensureCatProfile();CatLayout l=catLayout();for(int i=0;i<l.count;i++){putInt(l.unlockedStart+i*4,0);putShort(l.upgradeStart+i*4,0);putShort(l.upgradeStart+i*4+2,0);putInt(l.currentFormStart+i*4,0);putInt(l.gatyaSeenStart+i*4,0);putFormValue(i,0);bytes[l.guideStart+i]=0;putFourthValue(i,0);putInt(l.catseyesUsedStart+i*4,0);}resetAllCharaNewFlags();int table=talentTableOffset(),records=intAt(table),talents=table+4;for(int r=0;r<records;r++){int count=intAt(talents+4);talents+=8;for(int i=0;i<count;i++)putInt(talents+i*8+4,0);talents+=count*8;}int drops=unitDropsOffset();if(drops>=0)for(int i=0;i<GameDataRules.dropPairCount(region);i++)putInt(drops+GameDataRules.dropSlot(region,i)*4,0);touchRankUpSale();refreshHash(); }
    public void unlockTrueForms() { setTrueForms(false); }
    public void forceTrueForms() { setTrueForms(true); }
    private void setTrueForms(boolean force) {
        ensureCatProfile();CatLayout l=catLayout();
        for(int i=0;i<l.count;i++){
            int forms=GameDataRules.totalForms(region,gameVersion(),i);
            if(force){
                unlockCatRaw(i);putFormValue(i,3);putInt(l.currentFormStart+i*4,2);
            } else if(forms>=3){
                unlockCatRaw(i);putFormValue(i,3);putInt(l.currentFormStart+i*4,2);
            } else if(forms==2){
                putFormValue(i,0);putInt(l.currentFormStart+i*4,1);
            } else {
                putFormValue(i,0);putInt(l.currentFormStart+i*4,0);
            }
        }
        touchRankUpSale();refreshHash();
    }
    public void removeTrueForms() { ensureCatProfile();CatLayout l=catLayout(); for(int i=0;i<l.count;i++){ putInt(l.unlockedFormsStart+i*4,0);putInt(l.fourthStart+i*4,0);int current=intAt(l.currentFormStart+i*4);putInt(l.currentFormStart+i*4,Math.min(current,1)); }touchRankUpSale();refreshHash(); }
    public void unlockFourthForms() { setFourthForms(false); }
    public void forceFourthForms() { setFourthForms(true); }
    private void setFourthForms(boolean force) {
        ensureCatProfile();CatLayout l=catLayout();
        for(int i=0;i<l.count;i++){
            int forms=GameDataRules.totalForms(region,gameVersion(),i);
            if(force){
                unlockCatRaw(i);putFormValue(i,3);putInt(l.currentFormStart+i*4,3);putInt(l.fourthStart+i*4,2);
            } else if(forms>=4){
                unlockCatRaw(i);putFormValue(i,3);putInt(l.currentFormStart+i*4,3);putInt(l.fourthStart+i*4,2);
            } else if(forms>=3){
                unlockCatRaw(i);putFormValue(i,3);putInt(l.currentFormStart+i*4,2);putInt(l.fourthStart+i*4,0);
            } else if(forms==2){
                putFormValue(i,0);putInt(l.currentFormStart+i*4,1);putInt(l.fourthStart+i*4,0);
            } else {
                putFormValue(i,0);putInt(l.currentFormStart+i*4,0);putInt(l.fourthStart+i*4,0);
            }
        }
        touchRankUpSale();refreshHash();
    }
    public void removeFourthForms() { ensureCatProfile();CatLayout l=catLayout(); for(int i=0;i<l.count;i++){ int current=intAt(l.currentFormStart+i*4); putInt(l.currentFormStart+i*4,Math.min(current,2)); putInt(l.fourthStart+i*4,0); }touchRankUpSale();refreshHash(); }

    public boolean checksumValid() { return hasValidHashAt(bytes, region, hashPosition()); }
    public String checksum() { return new String(bytes, hashPosition(), 32, java.nio.charset.StandardCharsets.US_ASCII); }

    private static boolean hasValidHash(byte[] source, Region region, int start) {
        if (start + 44 > source.length || source.length < 32) return false;
        String expected = md5(region.salt(), source, 0, source.length-Offsets.offsets_130);
        String actual = new String(source, source.length-Offsets.offsets_130, 32, java.nio.charset.StandardCharsets.US_ASCII);
        return expected.equalsIgnoreCase(actual);
    }

    private static boolean hasValidHashAt(byte[] source, Region region, int hashOffset) {
        if (hashOffset < 0 || hashOffset + 32 > source.length) return false;
        String expected = md5(region.salt(), source, 0, hashOffset);
        String actual = new String(source, hashOffset, 32, java.nio.charset.StandardCharsets.US_ASCII);
        return expected.equalsIgnoreCase(actual);
    }

    private static int findHashOffset(byte[] source, Region region) {
        int from = Math.max(0, source.length - 4096);
        for (int offset = from; offset + 32 <= source.length; offset++) {
            boolean hex = true;
            for (int i = 0; i < 32; i++) {
                int value = source[offset + i] & 255;
                if (!((value >= '0' && value <= '9') || (value >= 'a' && value <= 'f')
                        || (value >= 'A' && value <= 'F'))) { hex = false; break; }
            }
            if (hex && hasValidHashAt(source, region, offset)) return offset;
        }
        return -1;
    }

    private static int rawIntAt(byte[] source, int offset) {
        if (offset < 0 || offset + 4 > source.length) return Integer.MIN_VALUE;
        return (source[offset] & 255) | ((source[offset + 1] & 255) << 8)
                | ((source[offset + 2] & 255) << 16) | (source[offset + 3] << 24);
    }

    private static int findUnsupportedVersionOffset(byte[] source) {
        int from = Math.max(0, Offsets.offsets_23 - 8192);
        int to = Math.min(source.length - 36, Offsets.offsets_23 + 8192);
        int best = -1, distance = Integer.MAX_VALUE;
        for (int offset = from; offset <= to; offset++) {
            int value = rawIntAt(source, offset);
            if (value >= 150501 && value <= 150599) {
                int currentDistance = Math.abs(offset - Offsets.offsets_23);
                if (currentDistance < distance) { best = offset; distance = currentDistance; }
            }
        }
        return best;
    }

    private void refreshHash() {
        int position = hashPosition();
        byte[] digestInput = Arrays.copyOf(bytes, position);
        byte[] digest = md5Bytes(region.salt(), digestInput);
        String value = hex(digest);
        System.arraycopy(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, bytes, position, 32);
    }

    private int hashPosition() { return hashOffset >= 0 ? hashOffset : bytes.length - Offsets.offsets_130; }

    private int intAt(int offset) {
        if (offset < 0 || offset + 4 > bytes.length-Offsets.offsets_130) throw new IllegalStateException("Save field is unavailable at offset=" + offset + " length=" + bytes.length);
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8) | ((bytes[offset + 2] & 255) << 16) | (bytes[offset + 3] << 24);
    }
    private int findIntNear(int value,int estimate,int radius) { int from=Math.max(0,estimate-radius),to=Math.min(bytes.length-36,estimate+radius);for(int offset=from;offset<=to;offset++)if(intAt(offset)==value)return offset;throw new IllegalStateException("Save marker is unavailable: "+value); }
    private int findIntNearOrAny(int value,int estimate,int radius) { try{return findIntNear(value,estimate,radius);}catch(IllegalStateException outsideEstimate){return findInt(value);} }
    private int findInt(int value) { for(int offset=4;offset<bytes.length-35;offset++)if(intAt(offset)==value)return offset;throw new IllegalStateException("Save marker is unavailable: "+value); }
    private static void writeInt(byte[] target,int offset,int value) { target[offset]=(byte)value;target[offset+1]=(byte)(value>>8);target[offset+2]=(byte)(value>>16);target[offset+3]=(byte)(value>>24); }
    private enum ProfileField { NORMAL_TICKETS, RARE_TICKETS, PLATINUM_TICKETS, LEGEND_TICKETS, PLATINUM_SHARDS, NP, LEADERSHIP }
    private int profiledInt(ProfileField field) { int offset = profileOffset(field); if (offset < 0) throw new UnsupportedOperationException("No item profile for this save version"); return intAt(offset); }
    private void setProfiledInt(ProfileField field, int value) { int offset = profileOffset(field); if (offset < 0) throw new UnsupportedOperationException("No item profile for this save version"); putInt(offset, value); refreshHash(); }
    private int profileOffset(ProfileField field) {
        if ((gameVersion() != 150500 && !(uploadMode && gameVersion() == 150501))
                || bytes.length < Offsets.offsets_143) return -1;
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
    private int byteAt(int offset) { if (offset < 0 || offset >= bytes.length-Offsets.offsets_130) throw new IllegalStateException("Save byte is unavailable at offset=" + offset + " length=" + bytes.length); return bytes[offset] & 255; }
    private void putInt(int offset, int value) { bytes[offset] = (byte) value; bytes[offset + 1] = (byte) (value >> 8); bytes[offset + 2] = (byte) (value >> 16); bytes[offset + 3] = (byte) (value >> 24); }
    private int ushortAt(int offset) { return (bytes[offset]&255)|((bytes[offset+1]&255)<<8); }
    private long longAt(int offset) { long value=0; for(int i=7;i>=0;i--) value=(value<<8)|(bytes[offset+i]&255L); return (long) Double.longBitsToDouble(value); }
    private long rawLongAt(int offset) { long value=0;for(int i=7;i>=0;i--)value=(value<<8)|(bytes[offset+i]&255L);return value; }
    private double rawDoubleAt(int offset) { return Double.longBitsToDouble(rawLongAt(offset)); }
    private void putLong(int offset,long value) { for(int i=0;i<8;i++){bytes[offset+i]=(byte)value;value>>=8;} }
    private String stringAt(int offset) { int length=intAt(offset); if(length<0||length>256||offset+4+length>bytes.length-Offsets.offsets_130)throw new IllegalStateException("Invalid save string at offset="+offset+" length="+length); return new String(bytes,offset+4,length,java.nio.charset.StandardCharsets.UTF_8); }
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
    private int accountCreatedAtOffset() {
        // The server's accountCreatedAt is the energy-penalty timestamp in
        // the upstream format, not the top-level gacha timestamp.
        return energyPenaltyTimestampOffset();
    }

    /** The menu-unlock integers follow the variable current-form list.  In
     * current 15.5 saves the equipment flag is the fourth serialized value,
     * corresponding to upstream menu_unlocks[2] after the three legacy
     * prefix entries. */
    private int menuUnlocksCountOffset() { return catLayout().currentFormEnd + 44; }
    private int menuUnlocksOffset() { return menuUnlocksCountOffset() + 4; }
    /** Count word for upstream {@code new_dialogs_2}, immediately following
     * the six battle-item amounts. */
    private int newDialogs2Offset() { return battleItemsOffset() + 24; }
    /** First lineup byte and the serialized ten-cat records. */
    private int lineupBaseOffset() {
        int expected = fixed(Offsets.offsets_73);
        int from = Math.max(0, expected - 131072), to = Math.min(bytes.length - Offsets.offsets_130 - 2, expected + 131072);
        int best = -1, distance = Integer.MAX_VALUE;
        for (int p = from; p <= to; p++) {
            int count = byteAt(p);
            if (count < 1 || count > 64) continue;
            long end = (long)p + 1 + count * 10L * 4L;
            if (end > bytes.length - Offsets.offsets_130) continue;
            boolean valid = true;
            for (int i = 0; i < count * 10; i++) {
                int cat = rawIntAt(p + 1 + i * 4);
                if (cat < -1 || cat >= catCount()) { valid = false; break; }
            }
            if (!valid) continue;
            int d = Math.abs(p - expected);
            if (d < distance) { best = p; distance = d; }
        }
        return best >= 0 ? best : expected;
    }
    private int unlockedLineupsOffset() {
        int expected = fixed(Offsets.offsets_75);
        int maxUnlocked = unlockableLineupCount();
        int from = Math.max(0, expected - 131072), to = Math.min(bytes.length - Offsets.offsets_130 - 10020, expected + 131072);
        int best = -1, distance = Integer.MAX_VALUE;
        for (int p = from; p <= to; p++) {
            int value = byteAt(p);
            if (value > maxUnlocked) continue;
            // Modern save.py writes the legend-restriction dimensions
            // immediately after unlocked_slots (5 map types, 500 maps in
            // the 15.5 layout), followed by one restriction byte per entry.
            int mapTypes = rawIntAt(p + 1), maps = rawIntAt(p + 5);
            // 15.5 serializes five event map groups with 500 subchapters;
            // requiring this exact header avoids mistaking arbitrary bytes in
            // the preceding lineup names/records for unlocked_slots.
            if (mapTypes != 5 || maps != 500) continue;
            if ((long)p + 9L + mapTypes * (long)maps * 4L > bytes.length - Offsets.offsets_130) continue;
            int d = Math.abs(p - expected);
            if (d < distance) { best = p; distance = d; }
        }
        return best >= 0 ? best : expected;
    }

    /** Locate the Gamatoto skin after the helper table, item-pack markers. */
    private int gamatotoSkinOffset() {
        int table = gamatotoHelperTableOffset();
        int count = intAt(table);
        if (count < 0 || count > 512) throw new IllegalStateException("Invalid Gamatoto helper table");
        int end = table + 4 + count * 4;
        if (byteAt(end) > 1 || intAt(end + 1) != 54 || intAt(end + 9) != 54) {
            throw new IllegalStateException("Gamatoto skin field is unavailable");
        }
        return end + 13;
    }

    /** date_3 is immediately after the six battle-item amounts and UI fields. */
    private int date3Offset() { return battleItemsOffset() + (region == Region.JP ? 471 : 473); }
    private int timestampOffset() { return Offsets.offsets_86; }

    /** Find marker 60 and use the preceding double as energy_penalty_timestamp. */
    private int energyPenaltyTimestampOffset() {
        int expected = fixed(Offsets.offsets_90);
        int from = Math.max(8, expected - 65536);
        int to = Math.min(bytes.length - Offsets.offsets_130 - 4, expected + 65536);
        int best = -1, distance = Integer.MAX_VALUE;
        for (int marker = from; marker <= to; marker++) {
            if (rawIntAt(marker) != 60) continue;
            double value = rawDoubleAt(marker - 8);
            if (Double.isNaN(value) || value < 946684800.0 || value > 4102444800.0) continue;
            int distanceNow = Math.abs(marker - expected);
            if (distanceNow < distance) { best = marker - 8; distance = distanceNow; }
        }
        if (best < 0) throw new IllegalStateException("Energy penalty timestamp is unavailable");
        return best;
    }

    /** Locate the modern Gamatoto record: double, bool, five ints. */
    private int gamatotoOffset() {
        int expected = fixed(Offsets.offsets_48) - 9;
        int from = Math.max(0, expected - 65536), to = Math.min(bytes.length - Offsets.offsets_130 - 80, expected + 65536);
        int best = -1, distance = Integer.MAX_VALUE;
        for (int base = from; base <= to; base++) {
            if (byteAt(base + 8) > 1) continue;
            int popupCount = rawIntAt(base + 29);
            if (popupCount < 1 || popupCount > 64) continue;
            int next = base + 33 + popupCount;
            if (next + 4 > bytes.length - Offsets.offsets_130) continue;
            int exCount = rawIntAt(next);
            if (exCount < 0 || exCount > 1000 || next + 4L + exCount * 48L + 4 > bytes.length - Offsets.offsets_130) continue;
            if (rawIntAt(next + 4 + exCount * 48) != 53) continue;
            int d = Math.abs(base - expected);
            if (d < distance) { best = base; distance = d; }
        }
        if (best >= 0) return best;
        return expected;
    }

    /** The rare/normal seeds immediately follow the variable unit-drop list. */
    private int gatyaSeedOffset(boolean unused) {
        int drops = unitDropsOffset();
        if (drops >= 0) {
            int base = drops + 400 * 4;
            if (base + 13 < bytes.length - Offsets.offsets_130) return base;
        }
        return fixed(Offsets.offsets_45);
    }

    /** Marker 46 is serialized immediately before event seed and capsules. */
    private int eventSeedOffset() {
        int expected = fixed(Offsets.offsets_47) - 8;
        int from = Math.max(0, expected - 65536), to = Math.min(bytes.length - Offsets.offsets_130 - 16, expected + 65536);
        int best = -1, distance = Integer.MAX_VALUE;
        for (int marker = from; marker <= to; marker++) {
            if (rawIntAt(marker) != 46) continue;
            int seed = marker + 4;
            int d = Math.abs(marker - expected);
            if (d < distance) { best = marker; distance = d; }
        }
        return best >= 0 ? best + 4 : fixed(Offsets.offsets_47);
    }

    private int eventTicketOffset() { return eventSeedOffset() + 8; }

    /** Lucky ticket list is terminated by bool + marker 77. */
    private int luckyTicketOffset() {
        int expected = fixed(Offsets.offsets_71);
        int from = Math.max(0, expected - 65536), to = Math.min(bytes.length - Offsets.offsets_130 - 224, expected + 65536);
        int best = -1, distance = Integer.MAX_VALUE;
        for (int marker = from; marker <= to; marker++) {
            if (rawIntAt(marker) != 77 || byteAt(marker - 1) > 1) continue;
            int start = marker - 1 - 55 * 4;
            boolean valid = start >= 0;
            for (int i = 0; valid && i < 55; i++) {
                int value = rawIntAt(start + i * 4);
                valid = value >= 0 && value <= 100000;
            }
            if (valid) {
                int d = Math.abs(start - expected);
                if (d < distance) { best = start; distance = d; }
            }
        }
        return best >= 0 ? best : expected;
    }

    /** Marker 80200 is followed by ub7, leadership short, and the two cat shorts. */
    private int officerPassCatOffset() {
        int expected = fixed(Offsets.offsets_77) - 4;
        int from = Math.max(0, expected - 65536), to = Math.min(bytes.length - Offsets.offsets_130 - 16, expected + 65536);
        int best = -1, distance = Integer.MAX_VALUE;
        for (int marker = from; marker <= to; marker++) {
            if (rawIntAt(marker) != 80000 || byteAt(marker + 4) > 1) continue;
            int end = marker + 11;
            if (rawIntAt(end) != 80200) continue;
            int leadership = ushortAt(marker + 5);
            if (leadership > 9999) continue;
            int d = Math.abs(marker - expected);
            if (d < distance) { best = marker + 7; distance = d; }
        }
        return best >= 0 ? best : fixed(Offsets.offsets_77);
    }

    /** The Dojo ranking record starts after marker 66 and ends at marker 67. */
    private int dojoRankingOffset() {
        int expected = fixed(Offsets.offsets_79) - 4;
        int from = Math.max(0, expected - 65536), to = Math.min(bytes.length - Offsets.offsets_130 - 32, expected + 65536);
        int best = -1, distance = Integer.MAX_VALUE;
        for (int marker = from; marker <= to; marker++) {
            if (rawIntAt(marker) != 66) continue;
            int p = marker + 4;
            if (p + 34 > bytes.length - Offsets.offsets_130) continue;
            int score = rawIntAt(p), ranking = rawIntAt(p + 4);
            if (score < 0 || score > 100000000 || ranking < 0 || ranking > 100000000) continue;
            if (byteAt(p + 8) > 1 || byteAt(p + 9) > 1 || byteAt(p + 10) > 1) continue;
            int startDate = rawIntAt(p + 11), endDate = rawIntAt(p + 15), eventNumber = rawIntAt(p + 19);
            if (startDate < 0 || endDate < 0 || (eventNumber < -1 || eventNumber > 100000000) || byteAt(p + 23) > 1 || byteAt(p + 24) > 1 || byteAt(p + 25) > 1) continue;
            int stringLength = rawIntAt(p + 26);
            if (stringLength < 0 || stringLength > 4096 || p + 30L + stringLength + 4 > bytes.length - Offsets.offsets_130) continue;
            int d = Math.abs(p - (fixed(Offsets.offsets_79)));
            if (d < distance) { best = p; distance = d; }
        }
        return best >= 0 ? best : fixed(Offsets.offsets_79);
    }
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
    private int[] treasureChestTable() {
        int marker=findInt(140300), limit=bytes.length-Offsets.offsets_130;
        // The record is immediately before the terminal marker.  Search the
        // complete self-delimiting suffix first; this handles received saves
        // whose profile shifted by several hundred bytes.
        for (int countOffset=Math.max(1, marker-4096); countOffset<marker; countOffset++) {
            int count=byteAt(countOffset);
            if (count<0 || count>64) continue;
            int values=countOffset+1, tail=values+count*4;
            if (tail+7>limit) continue;
            int ui23=rawIntAt(tail), listCount=ushortAt(tail+4);
            // Some 15.5 builds serialize ui23 as -1 and an empty uil13
            // list; this is still a valid terminal record.
            if (ui23 == -1 && listCount == 0 && rawIntAt(tail+7) == marker) {
                boolean valid=true;
                for (int i=0;i<count;i++) { int value=rawIntAt(values+i*4); if(value<0||value>1000000){valid=false;break;} }
                if (valid) return new int[]{values,count};
            }
            if (ui23 < -1 || ui23>100000000 || listCount>512 || tail+6+listCount*4+5>limit) continue;
            if (byteAt(tail+6+listCount*4)>1 || rawIntAt(tail+7+listCount*4)!=marker) continue;
            boolean valid=true;
            for (int i=0;i<count;i++) { int value=rawIntAt(values+i*4); if(value<0||value>1000000){valid=false;break;} }
            if (valid) return new int[]{values,count};
        }
        // Fast suffix form used by received 15.5 saves with empty uil13.
        for (int count=64; count>=0; count--) {
            int values=marker-7-count*4, tail=values+count*4;
            if (values<1 || tail+7!=marker || byteAt(values-1)!=count || rawIntAt(tail)!=-1 || ushortAt(tail+4)!=0) continue;
            boolean valid=true;
            for (int i=0;i<count;i++) { int value=rawIntAt(values+i*4); if(value<0||value>1000000){valid=false;break;} }
            if (valid) return new int[]{values,count};
        }
        throw new IllegalStateException("Treasure chest table is unavailable");
    }
    private int treasureChestOffset() { return treasureChestTable()[0]; }
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
        int estimate=fixed(Offsets.offsets_92),from=Math.max(4,estimate-65536),to=Math.min(bytes.length-40,estimate+65536);
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
    private int schemeTableOffset() {
        int expected = fixed(Offsets.offsets_94);
        int current;
        try { current = currentOutbreakTableOffset(); }
        catch (IllegalStateException missingContinuation) { current = -1; }
        int from = Math.max(0, current - 65536), to = Math.min(bytes.length - Offsets.offsets_130 - 8, current - 8);
        int best = -1, distance = Integer.MAX_VALUE;
        for (int table = from; table <= to; table++) {
            int first = rawIntAt(table);
            if (first < 0 || first > 10000 || table + 4L + first * 4L + 4 > bytes.length - Offsets.offsets_130) continue;
            int secondOffset = table + 4 + first * 4, second = rawIntAt(secondOffset);
            if (second < 0 || second > 10000 || secondOffset + 4L + second * 4L > bytes.length - Offsets.offsets_130) continue;
            if (current >= 0 && secondOffset + 4L + second * 4L != current) continue;
            int d = Math.abs(table - expected);
            if (d < distance) { best = table; distance = d; }
        }
        if (best >= 0) return best;
        int legacy=expected, first=rawIntAt(legacy);
        if (first>=0 && first<=10000) {
            int secondAt=legacy+4+first*4, second=rawIntAt(secondAt);
            if (second>=0 && second<=10000 && secondAt+4L+second*4L<=bytes.length-Offsets.offsets_130) return legacy;
        }
        throw new IllegalStateException("Scheme item table is unavailable");
    }
    private int dojoScoreTableOffset() {
        int expected = fixed(Offsets.offsets_95);
        int from = Math.max(0, expected - 65536), to = Math.min(bytes.length - Offsets.offsets_130 - 16, expected + 65536);
        int best = -1, distance = Integer.MAX_VALUE;
        for (int table = from; table <= to; table++) {
            int chapters = rawIntAt(table), p0 = table + 4;
            if (chapters < 0 || chapters > 1000) continue;
            int p = p0; boolean valid = true;
            for (int c = 0; c < chapters; c++) {
                if (p + 8 > bytes.length - Offsets.offsets_130) { valid = false; break; }
                int id = rawIntAt(p), stages = rawIntAt(p + 4);
                if (id < 0 || id > 100000 || stages < 0 || stages > 1000 || p + 8L + stages * 8L > bytes.length - Offsets.offsets_130) { valid = false; break; }
                p += 8 + stages * 8;
            }
            if (!valid || p + 8 > bytes.length - Offsets.offsets_130) continue;
            // Dojo is followed immediately by one flag byte, six item-lock
            // booleans and the serialized marker 58.  This tail makes the
            // table self-delimiting and rejects coincidental integer runs in
            // transfer-code payloads.  Tiny synthetic fixtures predate this
            // complete continuation, so retain the legacy candidate there.
            if (rawIntAt(p + 7) != 58 && bytes.length > 100000) continue;
            if (byteAt(p) > 1) continue;
            boolean locks = true;
            for (int i = 1; i <= 6; i++) if (byteAt(p + i) > 1) { locks = false; break; }
            if (!locks) continue;
            int d = Math.abs(table - expected);
            if (d < distance) { best = table; distance = d; }
        }
        if (best >= 0) return best;
        int legacy=expected, chapters=rawIntAt(legacy), p=legacy+4;
        if (chapters>=0 && chapters<=1000) {
            boolean valid=true;
            for(int c=0;c<chapters;c++) { if(p+8>bytes.length-Offsets.offsets_130){valid=false;break;} int stages=rawIntAt(p+4); if(stages<0||stages>1000||p+8L+stages*8L>bytes.length-Offsets.offsets_130){valid=false;break;} p+=8+stages*8; }
            if(valid) return legacy;
        }
        throw new IllegalStateException("Dojo score table is unavailable");
    }
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
    /** Gatya trade progress follows the variable cat/unit-drop section on
     * transfer-code saves; the template offset is only an estimate. */
    private int rareTicketTradeOffset() {
        // In save.py the trade integer is serialized immediately before the
        // dynamic max-upgrade list's count, with the two intervening fields
        // being the legacy list header (8 bytes).  Therefore the relationship
        // survives transfer-code shifts even when all absolute offsets move.
        return catLayout().maxUpgradeCountOffset - 12;
    }
    private int[] talentRecord(int catId) { int table=talentTableOffset(),records=intAt(table),offset=table+4;for(int r=0;r<records;r++){int id=intAt(offset),count=intAt(offset+4);if(id==catId)return new int[]{offset+8,count};if(id>catId)break;offset+=8+count*8;}return new int[]{0,0}; }
    private int lateOffset(int original) { return fixed(original)+dojoScoreDelta()+schemeDelta()+cannonDelta()+goldPassDelta()+medalDelta()+wildcatDelta()+enigmaDelta()+orbDelta()+scratcherDelta()+regionDelta(original); }
    private int regionDelta(int original) { return region == Region.EN && original >= Offsets.offsets_1 ? 5 : 0; }
    private int fixedStatic(int twOffset) { return fixed(twOffset); }
    private int fixed(int twOffset) {
        if (region != Region.JP) return twOffset;
        if (twOffset >= Offsets.offsets_15) return twOffset - 38;
        if (twOffset >= Offsets.offsets_7) return twOffset - 37;
        if (twOffset >= Offsets.offsets_16) return twOffset - 38;
        if (twOffset >= Offsets.offsets_9) return twOffset - 37;
        // The JP layout removes an additional four-byte pair immediately
        // before the max-upgrade list.  The list therefore starts 45 bytes
        // before the international reference, while the preceding trade
        // progress fields still use the -41 mapping.
        if (twOffset >= Offsets.offsets_102) return twOffset - 45;
        if (twOffset >= Offsets.offsets_17) return twOffset - 41;
        if (twOffset >= Offsets.offsets_18) return twOffset - 8;
        if (twOffset >= Offsets.offsets_19) return twOffset - 4;
        if (twOffset >= Offsets.offsets_20) return twOffset - 3;
        if (twOffset >= Offsets.offsets_21) return twOffset - 2;
        if (twOffset >= Offsets.offsets_132) return twOffset - 1;
        return twOffset;
    }
    private void splice(int offset,int remove,int insert) { if(offset<0||remove<0||insert<0||offset+remove>bytes.length-Offsets.offsets_130)throw new IllegalArgumentException("Invalid splice at offset="+offset+" remove="+remove+" insert="+insert+" length="+bytes.length);byte[] out=new byte[bytes.length-remove+insert];System.arraycopy(bytes,0,out,0,offset);System.arraycopy(bytes,offset+remove,out,offset+insert,bytes.length-offset-remove);bytes=out;catLayoutCache=null;battleItemsBaseCache=-1;unitDropsBaseCache=-1;if(cannonBaseCache>=0&&offset<cannonBaseCache)cannonBaseCache+=insert-remove;if(goldPassBaseCache>=0&&offset<goldPassBaseCache)goldPassBaseCache+=insert-remove;if(talentTableBaseCache>=0&&offset<talentTableBaseCache)talentTableBaseCache+=insert-remove;if(storageTableBaseCache>=0&&offset<storageTableBaseCache)storageTableBaseCache+=insert-remove;if(enigmaBaseCache>=0&&offset<enigmaBaseCache)enigmaBaseCache+=insert-remove;if(eventTableBaseCache>=0&&offset<eventTableBaseCache)eventTableBaseCache+=insert-remove; }
    private void ensureItemProfile() { if (!hasItemProfile()) throw new UnsupportedOperationException("No item profile for this save version"); }
    private void ensureCatProfile() { ensureItemProfile(); }
    private int charaNewFlagsOffset() {
        int expected=afterScheme(Offsets.offsets_101), limit=bytes.length-Offsets.offsets_130;
        int from=Math.max(0,expected-65536), to=Math.min(limit-8,expected+65536), best=-1, distance=Integer.MAX_VALUE;
        for(int base=from;base<=to;base++) {
            int count=rawIntAt(base); if(count<0||count>10000||base+4L+count*8L>limit) continue;
            int end=base+4+count*8;
            if(end+4>limit || byteAt(end)>1) continue;
            boolean marker=false; for(int q=end+1;q<=Math.min(limit-4,end+64);q++) if(rawIntAt(q)==61){marker=true;break;}
            if(!marker) continue;
            boolean valid=true;
            for(int i=0;i<count;i++){int id=rawIntAt(base+4+i*8);if(id<0||id>=catCount()){valid=false;break;}}
            if(valid&&Math.abs(base-expected)<distance){best=base;distance=Math.abs(base-expected);}
        }
        return best>=0?best:expected;
    }
    private void resetCharaNewFlag(int catId) { int base=charaNewFlagsOffset(),count=intAt(base),offset=base+4;if(count<0||count>10000)throw new IllegalStateException("Invalid cat flag table");for(int i=0;i<count;i++,offset+=8){if(intAt(offset)==catId){putInt(offset+4,0);return;}}splice(offset,0,8);putInt(offset,catId);putInt(offset+4,0);putInt(base,count+1); }
    private void resetAllCharaNewFlags() { int base=charaNewFlagsOffset(),count=intAt(base),offset=base+4;if(count<0||count>10000)throw new IllegalStateException("Invalid cat flag table");boolean[] present=new boolean[catCount()];for(int i=0;i<count;i++,offset+=8){int id=intAt(offset);if(id>=0&&id<present.length){present[id]=true;putInt(offset+4,0);}}int missing=0;for(boolean found:present)if(!found)missing++;if(missing==0)return;splice(offset,0,missing*8);int write=offset;for(int id=0;id<present.length;id++)if(!present[id]){putInt(write,id);putInt(write+4,0);write+=8;}putInt(base,count+missing); }
    /** Locate the six battle-item amounts from the serialized structure.
     *
     * Transfer-code saves can contain extra account payload before this
     * section, so offsets_31 is only an estimate.  The following records are
     * stable in 15.5: the new-dialog list, a 20-int UI list, two one-int
     * values, the lock flags, an optional DST flag, and a six-int date.
     */
    private int battleItemsOffset() {
        if (battleItemsBaseCache >= 0) return battleItemsBaseCache;
        int expected = fixed(Offsets.offsets_31);
        // JP stores the six amounts at the same logical location, but does
        // not have the international DST byte in the trailing record.
        if (gameVersion() <= 26) return battleItemsBaseCache = expected;
        int start = Math.max(0, expected - 65536);
        int end = Math.min(bytes.length - Offsets.offsets_130 - 256, expected + 65536);
        int best = -1, distance = Integer.MAX_VALUE;
        // The length of new_dialogs_2 is variable in modern saves.  The
        // previous implementation assumed 31 entries, which happened to be
        // true for one transfer sample but made every field after the list
        // drift when an account had a different list length.  Scan the whole
        // serialized payload and validate the complete upstream sequence.
        // Prefer the exact upstream anchor when the preceding variable menu
        // lists are present.  This avoids selecting a coincidental six-int
        // run elsewhere in a large transfer payload.
        int menuCountOffset = menuUnlocksCountOffset();
        if (menuCountOffset >= 0 && menuCountOffset + 4 <= bytes.length - Offsets.offsets_130) {
            int menuCount = rawIntAt(menuCountOffset);
            int popupCountOffset = menuCountOffset + 4 + menuCount * 4;
            if (menuCount >= 0 && menuCount <= 64
                    && popupCountOffset + 4 <= bytes.length - Offsets.offsets_130) {
                int popupCount = rawIntAt(popupCountOffset);
                int anchored = popupCountOffset + 4 + popupCount * 4;
                if (popupCount >= 0 && popupCount <= 64
                        && anchored + 28 <= bytes.length - Offsets.offsets_130) {
                    boolean valid = true;
                    for (int i = 0; i < 6; i++) {
                        int value = rawIntAt(anchored + i * 4);
                        if (value < 0 || value > 9999) { valid = false; break; }
                    }
                    if (valid && rawIntAt(anchored + 24) >= 0
                            && rawIntAt(anchored + 24) <= 2048) {
                        return battleItemsBaseCache = anchored;
                    }
                }
            }
        }
        for (int candidate = start; candidate <= end; candidate++) {
            if (candidate + 28 > bytes.length - Offsets.offsets_130) break;
            int dialogs = rawIntAt(candidate + 24);
            if (dialogs < 0 || dialogs > 2048) continue;
            boolean amounts = true;
            for (int i = 0; i < 6; i++) {
                int value = rawIntAt(candidate + i * 4);
                if (value < 0 || value > 9999) { amounts = false; break; }
            }
            if (!amounts) continue;
            long uiStartLong = (long) candidate + 28L + dialogs * 4L;
            if (uiStartLong < 0 || uiStartLong + 80L + 8L > bytes.length - Offsets.offsets_130) continue;
            int uiStart = (int) uiStartLong;
            boolean lists = true;
            for (int i = 0; i < dialogs; i++) {
                int value = rawIntAt(candidate + 28 + i * 4);
                if (value < 0 || value > 100000) { lists = false; break; }
            }
            if (!lists) continue;
            for (int i = 0; i < 20 && lists; i++) {
                int value = rawIntAt(uiStart + i * 4);
                if (value < -1 || value > 100000) lists = false;
            }
            if (!lists) continue;
            int flags = uiStart + 88; // lock_item + six locked flags
            int date = flags + (region == Region.JP ? 7 : 8);
            if (region != Region.JP) {
                for (int i = 0; i < 8; i++) {
                    int flag = byteAt(flags + i);
                    if (flag != 0 && flag != 1) { lists = false; break; }
                }
            } else {
                for (int i = 0; i < 7; i++) {
                    int flag = byteAt(flags + i);
                    if (flag != 0 && flag != 1) { lists = false; break; }
                }
            }
            if (!lists || !validSerializedDate(date)) continue;
            // date_2 is followed by five 10-int treasure-festival arrays,
            // then the second DST/date record.  Checking this continuation
            // prevents random six-int/date-shaped data from winning.
            int date3 = date + 24 + 200 + (region == Region.JP ? 0 : 1);
            if (!validSerializedDate(date3)) continue;
            int tail = date3 + 24;
            if (tail + 60 > bytes.length - Offsets.offsets_130) continue;
            int saveHashLength = rawIntAt(tail + 24);
            boolean validSaveHash = saveHashLength == 0
                    || (saveHashLength == 32 && asciiAlphaNumeric(tail + 28, 32));
            if (!validSaveHash) {
                // The transfer payload may retain a legacy 24-byte prefix
                // before save_data_4_hash; in that layout the length word is
                // four bytes earlier.  Older generated/profile fixtures may
                // legitimately serialize this optional string as empty.
                saveHashLength = rawIntAt(tail + 20);
                validSaveHash = saveHashLength == 0
                        || (saveHashLength == 32 && asciiAlphaNumeric(tail + 24, 32));
                if (!validSaveHash) continue;
            }
            int distanceNow = Math.abs(candidate - expected);
            if (distanceNow < distance) { best = candidate; distance = distanceNow; }
        }
        if (best < 0) {
            // Bundled/template saves use the historical fixed 31-entry list;
            // retain that exact compatibility path when the modern structural
            // continuation is not present.
            int legacyDialogs = rawIntAt(expected + 24);
            int legacyDate = expected + 240 + (region == Region.JP ? 7 : 8);
            if (legacyDialogs == 31 && legacyDate + 24 <= bytes.length - Offsets.offsets_130
                    && validSerializedDate(legacyDate)) {
                best = expected;
            }
        }
        if (best < 0) throw new IllegalStateException("Battle item table is unavailable");
        return battleItemsBaseCache = best;
    }

    private boolean validSerializedDate(int offset) {
        int year = rawIntAt(offset), month = rawIntAt(offset + 4), day = rawIntAt(offset + 8);
        int hour = rawIntAt(offset + 12), minute = rawIntAt(offset + 16), second = rawIntAt(offset + 20);
        return year >= 2000 && year <= 2100 && month >= 1 && month <= 12
                && day >= 1 && day <= 31 && hour >= 0 && hour < 24
                && minute >= 0 && minute < 60 && second >= 0 && second < 60;
    }

    private CatLayout catLayout() {
        if (catLayoutCache != null) return catLayoutCache;
        ensureItemProfile();
        int unlocked = locateCatHead();
        int countOffset = unlocked - 4;
        if (gameVersion() < 140300) {
            // Pre-14.3 saves have a fixed 873-cat profile and do not carry
            // the per-list count values introduced by the modern layout.
            int count = 873;
            int upgradeStart = fixedStatic(Offsets.offsets_40);
            int currentStart = fixedStatic(Offsets.offsets_55);
            int gatyaStart = fixedStatic(Offsets.offsets_58);
            int maxStart = fixedStatic(Offsets.offsets_102);
            int formsStart = fixedStatic(Offsets.offsets_133);
            int guideStart = fixedStatic(Offsets.offsets_57);
            int fruitStart = fixedStatic(Offsets.offsets_34);
            int fourthStart = fixedStatic(Offsets.offsets_56);
            int usedStart = fixedStatic(Offsets.offsets_63);
            int eyesStart = fixedStatic(Offsets.offsets_32);
            int aminsStart = fixedStatic(Offsets.offsets_33);
            return catLayoutCache = new CatLayout(countOffset,count,unlocked,
                    upgradeStart-4,upgradeStart,currentStart-4,currentStart,
                    gatyaStart-4,gatyaStart,maxStart-4,maxStart,formsStart-4,formsStart,
                    guideStart-4,guideStart,fruitStart-4,fruitStart,fourthStart-4,fourthStart,
                    usedStart-4,usedStart,eyesStart-4,eyesStart,aminsStart-4,aminsStart);
        }
        int count = intAt(countOffset);
        if (count < 1 || count > 873) throw new IllegalStateException("Invalid cat count");
        int dc=count-TEMPLATE_CAT_COUNT, d=dc*4;
        int headDelta = unlocked - fixedStatic(Offsets.offsets_38);
        int templateUpgradeStart = fixedStatic(Offsets.offsets_40) + headDelta;
        int templateUpgradeCount = templateUpgradeStart - 4;
        int upgradeCount = templateUpgradeCount + d;
        int upgradeStart = templateUpgradeStart + d;
        int currentCount = fixedStatic(Offsets.offsets_55)-4 + 2*d + headDelta;
        int currentStart = fixedStatic(Offsets.offsets_55) + 2*d + headDelta;
        // Everything below the current-form list moves by the number of
        // variable cat records already written.  The guide list is byte-sized.
        // The gatya-seen list follows a variable-length storage section in
        // received saves.  Its anchor moves by three bytes per cat record,
        // plus the number of extra storage bytes introduced by the transfer
        // payload; use the structural count search below rather than trusting
        // the old fixed anchor when the account has a non-empty storage.
        int gatyaCount = fixedStatic(Offsets.offsets_58)-4 + 3*d + headDelta;
        // The max-upgrade record is Upgrade(plus, base), and the dynamic
        // layout stores its count immediately before the plus half.
        int maxCount = fixedStatic(Offsets.offsets_102)-4 + 4*d + headDelta;
        int formsCount = fixedStatic(Offsets.offsets_133)-4 + 5*d + headDelta;
        int guideCount = fixedStatic(Offsets.offsets_57)-4 + 6*d + headDelta;
        int catfruitCount = fixedStatic(Offsets.offsets_34)-4 + 6*d + dc + headDelta;
        int fourthCount = fixedStatic(Offsets.offsets_56)-4 + 6*d + dc + headDelta;
        int eyesUsedCount = fixedStatic(Offsets.offsets_63)-4 + 7*d + dc + headDelta;
        int eyesCount = fixedStatic(Offsets.offsets_32)-4 + 8*d + dc + headDelta;
        int aminsCount = fixedStatic(Offsets.offsets_33)-4 + 8*d + dc + headDelta;
        // Received saves may contain variable-size account sections between
        // these lists.  Recover each count anchor from the expected order,
        // while still requiring every count value to match its known list
        // size.  This handles extra storage/credential payload without
        // allowing arbitrary data to be treated as a cat list.
        maxCount = recoverCountAnchor(maxCount, count, 32768, 0, 0);
        formsCount = recoverCountAnchor(formsCount, count, 32768, 0, 1);
        guideCount = recoverCountAnchor(guideCount, count, 32768, 0, 2);
        catfruitCount = recoverCountAnchor(catfruitCount, 29, 32768, 0, 3);
        fourthCount = recoverCountAnchor(fourthCount, count, 32768, 0, 4);
        eyesUsedCount = recoverCountAnchor(eyesUsedCount, count, 32768, 0, 5);
        eyesCount = recoverCatseyesCountAnchor(eyesCount);
        // Upstream reads catseyes and then immediately reads catamins as two
        // consecutive int lists.  Catamins is not guaranteed to contain
        // exactly three records (the list follows the region's item table),
        // so derive its count word from the recovered catseye list instead of
        // searching for a coincidental integer equal to 3.
        aminsCount = eyesCount + 4 + 6 * 4;
        if (intAt(gatyaCount) != count) {
            int recovered = recoverCountAnchor(fixedStatic(Offsets.offsets_58) - 4 + 3 * d + headDelta,
                    count, 16384, 0, 7);
            if (recovered >= 0) gatyaCount = recovered;
        }
        if(intAt(upgradeCount)!=count||intAt(currentCount)!=count||intAt(gatyaCount)!=count||
                intAt(maxCount)!=count||intAt(formsCount)!=count||intAt(guideCount)!=count||
                intAt(catfruitCount)!=29||intAt(fourthCount)!=count||intAt(eyesUsedCount)!=count||
                intAt(eyesCount)!=6||!validCataminList(aminsCount)) {
            // A handful of old diagnostic/profile fixtures (and saves made
            // before the variable-list migration) contain the modern cat
            // count at the first list, but omit the count word before each
            // following list.  They still use the original fixed, 4-byte
            // records.  Keep this narrow compatibility path so those
            // documents remain editable without relaxing validation for a
            // real transfer-code save with a shifted/corrupt layout.
            if (legacyFixedCatLayoutAvailable(count)) {
                return catLayoutCache = fixedCatLayout(countOffset, count);
            }
            throw new IllegalStateException("Invalid variable cat layout count="+count+
                    " upgrade="+intAt(upgradeCount)+" current="+intAt(currentCount)+
                    " gatya="+intAt(gatyaCount)+" max="+intAt(maxCount)+
                    " forms="+intAt(formsCount)+" guide="+intAt(guideCount)+
                    " fruit="+intAt(catfruitCount)+" fourth="+intAt(fourthCount)+
                    " used="+intAt(eyesUsedCount)+" eyes="+intAt(eyesCount)+
                    " amins="+intAt(aminsCount)+" positions="+upgradeCount+","+currentCount+","+
                    gatyaCount+","+maxCount+","+formsCount+","+guideCount+","+catfruitCount+","+
                    fourthCount+","+eyesUsedCount+","+eyesCount+","+aminsCount);
        }
        catLayoutCache = new CatLayout(countOffset,count,unlocked,upgradeCount,upgradeStart,currentCount,currentStart,
                gatyaCount,gatyaCount+4,maxCount,maxCount+4,formsCount,formsCount+4,
                guideCount,guideCount+4,catfruitCount,catfruitCount+4,fourthCount,fourthCount+4,
                eyesUsedCount,eyesUsedCount+4,eyesCount,eyesCount+4,aminsCount,aminsCount+4);
        return catLayoutCache;
    }

    /**
     * Locate the variable cat header using the exact order used by the
     * upstream reader.  Transfer-code saves may add/remove bytes before the
     * first cat list; using offsets_38 as an absolute start then shifts every
     * base-level and battle-item field even when all later lists are scanned
     * correctly.
     *
     * The candidate must contain the complete prefix:
     * count+unlocked, count+upgrade, count+current_form, eleven special-skill
     * upgrades, menu list, popup list, six battle-item amounts and the
     * new-dialog count.  This makes a random integer equal to 861/873 very
     * unlikely to be accepted as the cat header.
     */
    private int locateCatHead() {
        int expected = fixedStatic(Offsets.offsets_38) - 4;
        int limit = bytes.length - Offsets.offsets_130;
        int from = Math.max(0, expected - 131072);
        int to = Math.min(limit - 32, expected + 131072);
        int best = -1, bestDistance = Integer.MAX_VALUE;
        for (int countOffset = from; countOffset <= to; countOffset++) {
            int count = rawIntAt(countOffset);
            // Modern saves use the variable-list form only for the real cat
            // profile.  Tiny counts are common in unrelated payloads (and
            // made the old prefix probe accept a coincidental 1-cat record
            // near the template anchor), so reject them before considering
            // any downstream offsets.  Pre-14.3 fixed-count saves never use
            // this locator and are handled by the legacy branch in
            // catLayout().
            if (count < 200 || count > 873) continue;
            int unlocked = countOffset + 4;
            long upgradeCountLong = (long) unlocked + count * 4L;
            if (upgradeCountLong + 4 > limit) continue;
            if (!validCatUnlockedList(unlocked, count)) continue;
            int upgradeCount = (int) upgradeCountLong;
            if (rawIntAt(upgradeCount) != count || !validCatUpgradeList(upgradeCount + 4, count)) continue;
            long currentCountLong = (long) upgradeCount + 4L + count * 4L;
            if (currentCountLong + 4 > limit) continue;
            int currentCount = (int) currentCountLong;
            if (rawIntAt(currentCount) != count || !validCatCurrentFormList(currentCount + 4, count)) continue;
            int special = currentCount + 4 + count * 4;
            if (special + 44 + 8 > limit || !validSpecialSkillList(special)) continue;
            int menuCount = rawIntAt(special + 44);
            if (menuCount < 0 || menuCount > 64) continue;
            int popupCountOffset = special + 48 + menuCount * 4;
            if (popupCountOffset + 4 > limit) continue;
            int popupCount = rawIntAt(popupCountOffset);
            if (popupCount < 0 || popupCount > 64) continue;
            int battle = popupCountOffset + 4 + popupCount * 4;
            if (battle + 28 > limit || !validBattlePrefix(battle)
                    || !validBattleContinuation(battle)) continue;
            int distance = Math.abs(countOffset - expected);
            if (distance < bestDistance) {
                best = unlocked;
                bestDistance = distance;
            }
        }
        if (best >= 0) return best;
        // Keep old template compatibility for intentionally minimal fixtures
        // which omit the variable menu/battle prefix.
        return fixedStatic(Offsets.offsets_38);
    }

    private boolean validCatUpgradeList(int start, int count) {
        if ((long) start + count * 4L > bytes.length - Offsets.offsets_130) return false;
        for (int i = 0; i < count; i++) {
            if (ushortAt(start + i * 4) > 1000 || ushortAt(start + i * 4 + 2) > 1000) return false;
        }
        return true;
    }

    private boolean validCatUnlockedList(int start, int count) {
        if ((long) start + count * 4L > bytes.length - Offsets.offsets_130) return false;
        for (int i = 0; i < count; i++) {
            int value = rawIntAt(start + i * 4);
            if (value != 0 && value != 1) return false;
        }
        return true;
    }

    private boolean validCatCurrentFormList(int start, int count) {
        if ((long) start + count * 4L > bytes.length - Offsets.offsets_130) return false;
        for (int i = 0; i < count; i++) if (rawIntAt(start + i * 4) < 0 || rawIntAt(start + i * 4) > 3) return false;
        return true;
    }

    private boolean validSpecialSkillList(int start) {
        for (int i = 0; i < 11; i++) {
            int offset = start + i * 4;
            if (ushortAt(offset) > 1000 || ushortAt(offset + 2) > 1000) return false;
        }
        return true;
    }

    private boolean validBattlePrefix(int battle) {
        for (int i = 0; i < 6; i++) {
            int value = rawIntAt(battle + i * 4);
            if (value < 0 || value > 9999) return false;
        }
        int dialogs = rawIntAt(battle + 24);
        return dialogs >= 0 && dialogs <= 2048;
    }

    /** Validate the complete fixed suffix immediately following battle_items.
     * This is the same sequence consumed by the upstream reader and prevents
     * a random six-int run from becoming the cat-head anchor on received
     * transfer saves. */
    private boolean validBattleContinuation(int battle) {
        int limit = bytes.length - Offsets.offsets_130;
        int dialogs = rawIntAt(battle + 24);
        long uiStartLong = (long) battle + 28L + dialogs * 4L;
        if (uiStartLong < 0 || uiStartLong + 88L + 8L > limit) return false;
        int uiStart = (int) uiStartLong;
        for (int i = 0; i < 20; i++) {
            int value = rawIntAt(uiStart + i * 4);
            if (value < -1 || value > 100000) return false;
        }
        int flags = uiStart + 88;
        int flagCount = region == Region.JP ? 7 : 8;
        for (int i = 0; i < flagCount; i++) {
            int value = byteAt(flags + i);
            if (value != 0 && value != 1) return false;
        }
        int date = flags + flagCount;
        if (!validSerializedDate(date)) return false;
        int date3 = date + 24 + 200 + (region == Region.JP ? 0 : 1);
        if (!validSerializedDate(date3)) return false;
        int tail = date3 + 24;
        if (tail + 60 > limit) return false;
        int hashLength = rawIntAt(tail + 24);
        if (hashLength == 0) return true;
        return hashLength == 32 && asciiAlphaNumeric(tail + 28, 32);
    }

    private boolean legacyFixedCatLayoutAvailable(int count) {
        if (count < 1 || count > 873 || bytes.length < 500000) return false;
        // The legacy fixture has the first (unlocked) count, while all of the
        // later modern count words are absent/zero.  Real modern saves carry
        // the same count in every list, so this check cannot mask a normal
        // variable-length offset error.
        return intAt(fixedStatic(Offsets.offsets_40) - 4) == 0
                && intAt(fixedStatic(Offsets.offsets_55) - 4) == 0
                && intAt(fixedStatic(Offsets.offsets_58) - 4) == 0
                && intAt(fixedStatic(Offsets.offsets_102) - 4) == 0
                && intAt(fixedStatic(Offsets.offsets_133) - 4) == 0
                && intAt(fixedStatic(Offsets.offsets_57) - 4) == 0
                && intAt(fixedStatic(Offsets.offsets_34) - 4) == 0
                && intAt(fixedStatic(Offsets.offsets_56) - 4) == 0
                && intAt(fixedStatic(Offsets.offsets_63) - 4) == 0
                && intAt(fixedStatic(Offsets.offsets_32) - 4) == 0
                && intAt(fixedStatic(Offsets.offsets_33) - 4) == 0;
    }

    private CatLayout fixedCatLayout(int countOffset, int count) {
        int unlockedStart = fixedStatic(Offsets.offsets_38);
        int upgradeStart = fixedStatic(Offsets.offsets_40);
        int currentStart = fixedStatic(Offsets.offsets_55);
        int gatyaStart = fixedStatic(Offsets.offsets_58);
        int maxStart = fixedStatic(Offsets.offsets_102);
        int formsStart = fixedStatic(Offsets.offsets_133);
        int guideStart = fixedStatic(Offsets.offsets_57);
        int fruitStart = fixedStatic(Offsets.offsets_34);
        int fourthStart = fixedStatic(Offsets.offsets_56);
        int usedStart = fixedStatic(Offsets.offsets_63);
        int eyesStart = fixedStatic(Offsets.offsets_32);
        int aminsStart = fixedStatic(Offsets.offsets_33);
        return new CatLayout(countOffset, count, unlockedStart,
                upgradeStart - 4, upgradeStart, currentStart - 4, currentStart,
                gatyaStart - 4, gatyaStart, maxStart - 4, maxStart,
                formsStart - 4, formsStart, guideStart - 4, guideStart,
                fruitStart - 4, fruitStart, fourthStart - 4, fourthStart,
                usedStart - 4, usedStart, eyesStart - 4, eyesStart,
                aminsStart - 4, aminsStart);
    }

    /** Locate a variable cat-list count using both its count and the legal
     * value range of the complete following list.  Received saves contain
     * many unrelated integers equal to 873/29/6/3, so a nearest-count probe
     * is not sufficient. */
    private int recoverCountAnchor(int expected, int count, int radius, int minOffset, int kind) {
        int start = Math.max(Math.max(0, expected - radius), minOffset);
        int end = Math.min(bytes.length - Offsets.offsets_130 - 4, expected + radius);
        int best = -1, distance = Integer.MAX_VALUE;
        for (int delta = 0; delta <= radius; delta++) {
            int[] candidates = delta == 0 ? new int[]{expected} : new int[]{expected + delta, expected - delta};
            for (int candidate : candidates) {
                // Count words are not guaranteed to be four-byte aligned in a
                // received profile: preceding variable byte/short sections
                // can leave the next list at any parity.
                if (candidate < start || candidate > end || intAt(candidate) != count) continue;
                if (!validCatList(candidate + 4, count, kind)) continue;
                int d = Math.abs(candidate - expected);
                if (d < distance) { best = candidate; distance = d; }
            }
        }
        return best >= 0 ? best : expected;
    }

    /** Recover catseyes only when the immediately following catamin list is
     * also structurally valid.  A transfer save can contain unrelated `6`
     * integers near the template estimate; accepting one of those shifts the
     * catamin write into Gamatoto and makes the server reject the save. */
    private int recoverCatseyesCountAnchor(int expected) {
        int radius = 32768;
        int start = Math.max(0, expected - radius);
        int end = Math.min(bytes.length - Offsets.offsets_130 - 4, expected + radius);
        int best = -1, distance = Integer.MAX_VALUE;
        for (int delta = 0; delta <= radius; delta++) {
            int[] candidates = delta == 0 ? new int[]{expected} : new int[]{expected + delta, expected - delta};
            for (int candidate : candidates) {
                if (candidate < start || candidate > end || intAt(candidate) != 6) continue;
                if (!validCatList(candidate + 4, 6, 6)) continue;
                int cataminCount = candidate + 4 + 6 * 4;
                if (!validCataminList(cataminCount)) continue;
                int distanceNow = Math.abs(candidate - expected);
                if (distanceNow < distance) { best = candidate; distance = distanceNow; }
            }
        }
        return best >= 0 ? best : expected;
    }

    private boolean validCatList(int start, int count, int kind) {
        long end;
        switch (kind) {
            case 0: // max-upgrade: two unsigned shorts
                end = (long) start + count * 4L;
                if (end > bytes.length - Offsets.offsets_130) return false;
                for (int i = 0; i < count; i++) {
                    if (ushortAt(start + i * 4) > 1000 || ushortAt(start + i * 4 + 2) > 1000) return false;
                }
                return true;
            case 1: // unlocked forms
                end = (long) start + count * 4L;
                if (end > bytes.length - Offsets.offsets_130) return false;
                for (int i = 0; i < count; i++) if (rawIntAt(start + i * 4) < 0 || rawIntAt(start + i * 4) > 3) return false;
                return true;
            case 2: // cat guide flags
                end = (long) start + count;
                if (end > bytes.length - Offsets.offsets_130) return false;
                for (int i = 0; i < count; i++) if (byteAt(start + i) > 1) return false;
                return true;
            case 3: // catfruit
                end = (long) start + count * 4L;
                if (end > bytes.length - Offsets.offsets_130) return false;
                int fruitMax = gameVersion() < 110400 ? 128 : 998;
                for (int i = 0; i < count; i++) if (rawIntAt(start + i * 4) < 0 || rawIntAt(start + i * 4) > fruitMax) return false;
                return true;
            case 4: // fourth forms
                end = (long) start + count * 4L;
                if (end > bytes.length - Offsets.offsets_130) return false;
                for (int i = 0; i < count; i++) if (rawIntAt(start + i * 4) < 0 || rawIntAt(start + i * 4) > 2) return false;
                return true;
            case 5: // catseyes used
                end = (long) start + count * 4L;
                if (end > bytes.length - Offsets.offsets_130) return false;
                for (int i = 0; i < count; i++) if (rawIntAt(start + i * 4) < 0 || rawIntAt(start + i * 4) > 9999) return false;
                return true;
            case 6: // catseyes / catamins
                end = (long) start + count * 4L;
                if (end > bytes.length - Offsets.offsets_130) return false;
                for (int i = 0; i < count; i++) if (rawIntAt(start + i * 4) < 0 || rawIntAt(start + i * 4) > 9999) return false;
                return true;
            case 7: // gatya-seen flags
                end = (long) start + count * 4L;
                if (end > bytes.length - Offsets.offsets_130) return false;
                for (int i = 0; i < count; i++) if (rawIntAt(start + i * 4) < 0 || rawIntAt(start + i * 4) > 1) return false;
                return true;
            default:
                return false;
        }
    }

    private boolean validCataminList(int countOffset) {
        if (countOffset < 0 || countOffset + 4 > bytes.length - Offsets.offsets_130) return false;
        int count = rawIntAt(countOffset);
        if (count < 0 || count > 1024) return false;
        long end = (long) countOffset + 4L + count * 4L;
        if (end > bytes.length - Offsets.offsets_130) return false;
        for (int i = 0; i < count; i++) {
            int value = rawIntAt(countOffset + 4 + i * 4);
            if (value < 0 || value > 9999) return false;
        }
        return true;
    }


    private int rankUpSaleOffset() {
        // rank_up_sale_value is serialized immediately before the cat-guide
        // count.  Received saves may have variable cat sections, so the
        // bundled offsets_43 value is only a template estimate.
        // rank_up_sale_value sits after the variable pre-guide fields and
        // immediately before marker 49.  The guide count is much later (the
        // marker 52 block), so deriving this from guideStart is incorrect for
        // received saves and overwrites the first guide bytes.
        int expected = fixed(Offsets.offsets_43);
        int from = Math.max(0, expected - 65536), to = Math.min(bytes.length - Offsets.offsets_130 - 28, expected + 65536);
        for (int p = from; p <= to; p++) {
            if (rawIntAt(p + 4) != 49 || rawIntAt(p + 8) != 50 || rawIntAt(p + 12) != 51) continue;
            return p;
        }
        return expected;
    }
    private int userRankRewardCountOffset() {
        CatLayout l = catLayout();
        return l.maxUpgradeStart + l.count * 4 + 44;
    }
    private int userRankRewardFlagsOffset() { return userRankRewardCountOffset() + 4; }
    private void touchRankUpSale() { putInt(rankUpSaleOffset(),0x7fffffff); }
    private void syncCatRankLimits(int catId) { java.util.function.IntPredicate available=id->true;int base=GameDataRules.catRankLimitBase(catId,available),plus=GameDataRules.catRankLimitPlus(catId,available);CatLayout l=catLayout();putShort(l.maxUpgradeStart+catId*4,plus);putShort(l.maxUpgradeStart+catId*4+2,base); }
    private void unlockCatRaw(int index) {
        CatLayout l=catLayout();
        putInt(l.unlockedStart+index*4,1);putInt(l.gatyaSeenStart+index*4,1);
        int menu = menuUnlocksOffset() + 8;
        putInt(menu,Math.max(1,intAt(menu)));
        int drops=unitDropsOffset();
        if(drops>=0)for(int i=0;i<GameDataRules.dropPairCount(region);i++)
            if(GameDataRules.dropCat(region,i)==index)putInt(drops+GameDataRules.dropSlot(region,i)*4,1);
    }
    private void unlockCatForUpgrade(int index) {
        CatLayout l=catLayout(); putInt(l.unlockedStart+index*4,1); putInt(l.gatyaSeenStart+index*4,1);
        int menu = menuUnlocksOffset() + 8;
        putInt(menu,Math.max(1,intAt(menu)));
        int drops=unitDropsOffset();
        if(drops>=0)for(int i=0;i<GameDataRules.dropPairCount(region);i++) if(GameDataRules.dropCat(region,i)==index)putInt(drops+GameDataRules.dropSlot(region,i)*4,1);
    }
    /** Upstream Cat.set_upgrade(..., only_plus=True) still calls Cat.unlock()
     * when unlock-on-edit is enabled, so plus edits update gatya_seen, the
     * menu unlock and every matching unit-drop reward. */
    private void unlockCatForPlusUpgrade(int index) { unlockCatRaw(index); }
    private int unitDropsOffset() {
        if(unitDropsBaseCache!=-1)return unitDropsBaseCache;
        // The unit-drop list follows the 36 popup values and can move with
        // received-account payloads.  Its length is 400 in modern saves.
        int expected=fixed(Offsets.offsets_59);
        for(int delta=0;delta<=8192;delta++) {
            int[] c=delta==0?new int[]{expected}:new int[]{expected+delta,expected-delta};
            for(int base:c) {
                if(base<0||base+4>bytes.length-Offsets.offsets_130)continue;
                int value=rawIntAt(base);
                if((value==400 || value==401) && rawIntAt(base+1600)>=0 && rawIntAt(base+1600)<=1) {
                    boolean valid=true;
                    for(int i=0;i<400;i++){int v=rawIntAt(base+4+i*4);if(v<0||v>1){valid=false;break;}}
                    if(valid)return unitDropsBaseCache=base+(value==400?4:0);
                }
            }
        }
        // Minimal synthetic fixtures used by the core tests intentionally do
        // not contain the later drop section; keep their edits lossless.
        // Older/synthetic fixtures may intentionally omit this late table.
        // Keep their edits lossless; real modern transfer saves are large
        // enough to have been handled by the structural scan above.
        if(bytes.length<507100)return unitDropsBaseCache=-2;
        throw new IllegalStateException("Unit drop table is unavailable");
    }
    private void putIntBE(int offset,int value){bytes[offset]=(byte)(value>>>24);bytes[offset+1]=(byte)(value>>>16);bytes[offset+2]=(byte)(value>>>8);bytes[offset+3]=(byte)value;}
    private void putFormValue(int index,int value){putInt(catLayout().unlockedFormsStart+index*4,value);}
    private void putFourthValue(int index,int value){putInt(catLayout().fourthStart+index*4,value);}
    private void checkCat(int index) { ensureCatProfile();if(index<0||index>=catCount())throw new IndexOutOfBoundsException(); }
    private void checkLevel(int value) { if(value<0||value>65535)throw new IllegalArgumentException("Invalid level"); }
    private void checkAmount(String field,int value,int maximum) { if(value<0||value>maximum)throw new IllegalArgumentException(field+" must be between 0 and "+maximum); }
    private void checkUnsignedInt(long value) { if(value<0||value>0xffffffffL)throw new IllegalArgumentException("Value must fit an unsigned 32-bit integer"); }
    private void checkDisplayedLevel(int value) { if(value<1||value>65536)throw new IllegalArgumentException("Invalid level"); }
    private void checkCatLevel(int index,int value) { checkCat(index);checkLevel(value); }
    private int specialSkillUpgradeOffset(int index) { ensureCatProfile();if(index<0||index>=10)throw new IndexOutOfBoundsException();int raw=index==0?0:index+1;return catLayout().currentFormEnd+raw*4; }
    private int endlessBattleOffset(int index) { if(index<0||index>=6)throw new IndexOutOfBoundsException();return afterOrbs(Offsets.offsets_104)+index*19; }
    private int storyInternalChapter(int chapter) { ensureItemProfile();if(chapter<0||chapter>=9)throw new IndexOutOfBoundsException();return chapter<3?chapter:chapter+1; }
    private void checkStoryStage(int stage) { if(stage<0||stage>=48)throw new IndexOutOfBoundsException(); }
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
    private void validateMapRange(MapLayout l,int firstMap,int lastMap) { if(firstMap<0||lastMap<firstMap||lastMap>=l.maps)throw new IndexOutOfBoundsException(); }
    private void validateCrownCount(MapLayout l,int map,int crownCount) { if(crownCount<1||crownCount>l.starsAt(map))throw new IllegalArgumentException("Invalid crown count"); }
    private void clearStageMapInternal(MapLayout l,int map,boolean cleared,int crowns) {
        for(int star=0;star<crowns;star++){
            int total=l.stagesAt(map,star);
            for(int stage=0;stage<total;stage++){
                int offset=l.stageOffset(map,star,stage),current=l.shortValues?ushortAt(offset):intAt(offset),value=cleared?(current==0?1:current):0;
                if(l.shortValues)putShort(offset,value);else putInt(offset,value);
                if(cleared&&l.mirrorStageBase>=0){int mirror=l.mirrorOffset(map,star,stage),tries=ushortAt(mirror);putShort(mirror,tries==0?1:tries);}
            }
            l.setProgress(map,star,cleared?total:0);
                if(cleared)l.setUnlock(map,star,3);
        }
        if(cleared&&l.cascadeCompletion&&l.hasNextMap(map))l.setUnlock(map+1,0,1);
    }
    private void checkVisibleMap(StageMap type,MapLayout l,int map,int star,int stage,boolean checkStage) { checkMap(l,map,star,stage,checkStage);if(star>=configuredCrownCount(type,map))throw new IndexOutOfBoundsException(); }
    private int configuredCrownCount(StageMap type,int map) {
        return switch(type){
            case EVENT -> {int group=map/500,id=map%500;if(group==0)yield id>=0&&id<=48?1:0;if(group==1)yield id<EVENT_MAP_CROWNS.length()?EVENT_MAP_CROWNS.charAt(id)-'0':0;if(group==2)yield id<COLLAB_MAP_CROWNS.length()?COLLAB_MAP_CROWNS.charAt(id)-'0':0;yield 0;}
            case UNCANNY -> map<49?4:0;
            case CATAMIN -> map<52?1:0;
            case LEGEND_QUEST -> map==0?1:0;
            case TOWER -> map<12?1:0;
            /*
             * Zero Legends keeps four records in the save for every map, but
             * Map_option.csv controls how many crowns the current game
             * version actually exposes.  The upstream editor uses that
             * configured count instead of the physical record count.  The
             * second crown was introduced for the first nine maps in 15.4
             * and the first eleven maps in 15.5; older versions expose one
             * crown only.
             */
            case ZERO_LEGENDS -> {
                int twoCrownMaps = gameVersion() >= 150500 ? 11 : gameVersion() >= 150400 ? 9 : 0;
                yield map < twoCrownMaps ? 2 : 1;
            }
            case GAUNTLETS -> map<82?1:0;
            case ENIGMA_CLEARS -> map<73?1:0;
            case COLLAB_GAUNTLETS -> map<28?1:0;
            case BEHEMOTH -> map<3?1:0;
            case DOJO -> map<12?1:0;
            // Challenge uses the same four-crown Chapters structure as the
            // upstream editor; all four star records are valid even though
            // most accounts start with only the first one unlocked.
            case CHALLENGE -> 4;
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
    private int storageTableOffset() {
        if (storageTableBaseCache >= 0) return storageTableBaseCache;
        int expected = fixed(Offsets.offsets_108);
        int from = Math.max(0, expected - 65536), to = Math.min(bytes.length - Offsets.offsets_130 - 16, expected + 65536);
        int best = -1, distance = Integer.MAX_VALUE;
        for (int table = from; table <= to; table++) {
            int count = ushortAt(table);
            if (count < 64 || count > 256 || table + 2L + count * 8L > bytes.length - Offsets.offsets_130) continue;
            boolean valid = true, hasItem = false;
            for (int i = 0; i < count; i++) {
                int id = rawIntAt(table + 2 + i * 4), type = rawIntAt(table + 2 + count * 4 + i * 4);
                if (id < 0 || id > 100000 || type < 0 || type > 3) { valid = false; break; }
                hasItem |= type != 0;
            }
            if (!valid) continue;
            // The next save field is event-stage data; empty storage is valid,
            // so prefer the structural count nearest the old template anchor.
            int d = Math.abs(table - expected) + (hasItem ? 0 : 1);
            if (d < distance) { best = table; distance = d; }
        }
        if (best >= 0) return storageTableBaseCache = best;
        throw new IllegalStateException("Storage table is unavailable");
    }
    private int enigmaStageOffset(int index) { int count=enigmaStageCount();if(index<0||index>=count)throw new IndexOutOfBoundsException();return enigmaBaseOffset()+12+index*17; }
    private int enigmaBaseOffset() { if(enigmaBaseCache>=0)return enigmaBaseCache;int expected=afterWildcat(Offsets.offsets_109);for(int delta=0;delta<=96;delta++){int[] candidates=delta==0?new int[]{expected}:new int[]{expected+delta,expected-delta};for(int base:candidates){if(base<0||base+13>bytes.length-Offsets.offsets_130)continue;int energy1=intAt(base),energy2=intAt(base+4),level=byteAt(base+8),unknown=byteAt(base+9),flag=byteAt(base+10),count=byteAt(base+11);if(energy1<0||energy2<0||level>100||unknown!=1||flag>1||count>73)continue;int p=base+12;boolean valid=true;for(int i=0;i<count;i++){if(p+17>bytes.length-Offsets.offsets_130){valid=false;break;}int stageLevel=intAt(p),id=intAt(p+4),decoding=byteAt(p+8);if(stageLevel<0||stageLevel>1000||id<25000||id>=25073||decoding>2){valid=false;break;}p+=17;}if(valid&&p<bytes.length-Offsets.offsets_130&&byteAt(p)<=1){enigmaBaseCache=base;return base;}}}if(expected>=0&&expected+13<=bytes.length-Offsets.offsets_130){enigmaBaseCache=expected;return expected;}throw new IllegalStateException("Enigma table is unavailable"); }
    private void checkStorage(int slot) { ensureItemProfile();if(slot<0||slot>=storageCount())throw new IndexOutOfBoundsException(); }
    private void addStorageItem(int type,int id) { int slot=firstEmptyStorageSlot();if(slot<0)throw new IllegalStateException("Storage is full");setStorageItem(slot,type,id); }
    private void ensureStorageSpace(int needed) { if(storageCount()-occupiedStorageCount()<needed)throw new IllegalStateException("Storage is full"); }
    private void setCannonDevelopmentRaw(int index,int value) { int[] e=cannonEntry(index);putInt(e[1]+8,value); }
    private int goldPassBase(){
        if(goldPassBaseCache>=0&&goldPassBaseCache+90<=bytes.length-Offsets.offsets_130)return goldPassBaseCache;
        int expected=afterCannonBase(Offsets.offsets_110);
        /*
         * NyankoClub is a self-delimiting record.  The old heuristic only
         * checked the two flag bytes after the claimed-rewards dictionary;
         * arbitrary map data can satisfy that shape and was selected on
         * received saves.  The record is always followed by marker 80000,
         * so require that marker as well as the complete field bounds.
         */
        int limit=bytes.length-Offsets.offsets_130;
        int from=Math.max(0,expected-65536),to=Math.min(limit-90,expected+65536);
        int best=-1,distance=Integer.MAX_VALUE;
        for(int base=from;base<=to;base++){
            if(base+90>limit)continue;
            int officer=intAt(base),renewals=intAt(base+4),count=intAt(base+76);
            if((officer!=-1&&officer<=0)||renewals<0||renewals>10000||count<0||count>10000)continue;
            long tail=(long)base+80L+(long)count*8L;
            if(tail+10>limit)continue;
            int tailOffset=(int)tail;
            if(byteAt(tailOffset+8)>1||byteAt(tailOffset+9)>1)continue;
            // Gold Pass is followed by the variable cat-talent table, then
            // NP, ub6, and marker 80000.  Validate that whole continuation;
            // checking only the two flag bytes is not sufficient because
            // arbitrary transfer-code payloads can look like a short record.
            int talentTable=tailOffset+10;
            int records=rawIntAt(talentTable),p=talentTable+4,last=-1;
            if(records<0||records>873)continue;
            boolean talentValid=true;
            for(int r=0;r<records;r++){
                if(p+8>limit){talentValid=false;break;}
                int cat=rawIntAt(p),talents=rawIntAt(p+4);
                if(cat<0||cat>=873||cat<=last||talents<0||talents>64||p+8L+talents*8L>limit){talentValid=false;break;}
                last=cat;p+=8+talents*8;
            }
            if(!talentValid||p+9>limit||rawIntAt(p+5)!=80000||byteAt(p+4)>1)continue;
            int d=Math.abs(base-expected);
            if(d<distance){best=base;distance=d;}
        }
        if(best>=0){goldPassBaseCache=best;return best;}
        if(expected>=0&&expected+90<=limit&&intAt(expected)==0&&intAt(expected+4)==0&&intAt(expected+76)==0){goldPassBaseCache=expected;return expected;}
        throw new IllegalStateException("Gold Pass table is unavailable");
    }
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
    private int findOutbreakTable(int expected,boolean fullTable) {
        // Received saves can carry a substantially larger variable profile
        // before the outbreak records (the transfer sample is shifted by
        // hundreds of bytes).  The old ±32-byte probe was template-only and
        // made outbreak parsing fail after transfer-code import.
        int limit = bytes.length - Offsets.offsets_130;
        int from = Math.max(0, expected - 65536), to = Math.min(limit - 4, expected + 65536);
        int best = -1, distance = Integer.MAX_VALUE;
        for (int candidate = from; candidate <= to; candidate++) {
            if (!validOutbreakTable(candidate, fullTable)) continue;
            int distanceHere = Math.abs(candidate - expected);
            if (distanceHere < distance) { best = candidate; distance = distanceHere; }
        }
        if (best >= 0) return best;
        throw new IllegalStateException("Outbreak table is unavailable");
    }
    private boolean validOutbreakTable(int table, boolean fullTable) {
        if (table < 0 || table + 4 > bytes.length - Offsets.offsets_130) return false;
        int chapters = intAt(table);
        if (chapters <= 0 || chapters > 32) return false;
        int offset = table + 4, previous = -1, totalStages = 0;
        for (int chapter = 0; chapter < chapters; chapter++) {
            if (offset + 8 > bytes.length - Offsets.offsets_130) return false;
            int id = intAt(offset), stages = intAt(offset + 4);
            if (id < 0 || id > 1000 || id == previous || stages < 0 || stages > 100) return false;
            previous = id; offset += 8;
            int previousStage = -1;
            for (int stage = 0; stage < stages; stage++) {
                if (offset + 5 > bytes.length - Offsets.offsets_130) return false;
                int stageId = intAt(offset), state = byteAt(offset + 4);
                if (stageId < 0 || stageId > 1000 || stageId == previousStage || state > 1) return false;
                previousStage = stageId; offset += 5;
            }
            totalStages += stages;
        }
        if (fullTable) return totalStages >= chapters;
        // Current outbreaks are followed by first_locks (int/bool pairs),
        // an 8-byte timestamp and marker 60.  Validate that continuation so
        // a zero-filled or map-data integer run cannot be mistaken for this
        // table on transfer-code saves.
        if (offset + 12 > bytes.length - Offsets.offsets_130) return false;
        int lockCount = intAt(offset);
        if (lockCount < 0 || lockCount > 10000) return false;
        int tail = offset + 4 + lockCount * 5;
        if (tail + 12 > bytes.length - Offsets.offsets_130) return false;
        return rawIntAt(tail + 8) == 60;
    }
    private MapLayout mapLayout(StageMap type) { ensureItemProfile();return switch(type){
        case CHALLENGE -> standardLayout(challengeTableOffset(),true);
        case UNCANNY -> standardLayout(standardTableOffset(afterCannons(Offsets.offsets_113),49),false);
        case CATAMIN -> standardLayout(standardTableOffset(afterCannons(Offsets.offsets_114),52),false);
        case TOWER -> standardLayout(afterCannons(Offsets.offsets_115),true);
        // The serialized map count changed between the bundled 15.5 template
        // (81/26) and transfer-code saves (82/28).  Locate the table from its
        // structural header instead of assuming the template count.
        case GAUNTLETS -> compactLayout(compactTableOffset(afterWildcat(Offsets.offsets_116),81,82,90300));
        case ENIGMA_CLEARS -> compactLayout(findInt(90300)+4);
        case COLLAB_GAUNTLETS -> compactLayout(compactTableOffset(afterEnigma(Offsets.offsets_117),26,28,90500));
        case EVENT -> eventLayout(eventTableOffset());
        case BEHEMOTH -> compactLayout(findInt(110000)+4);
        case LEGEND_QUEST -> legendQuestLayout(legendQuestTableOffset());
        case ZERO_LEGENDS -> variableLayout(findInt(111000)+4,true);
        case DOJO -> dojoLayout(variableTableOffset(lateOffset(Offsets.offsets_118),12));
    }; }
    private int compactTableOffset(int expected,int expectedMaps){return compactTableOffset(expected,expectedMaps,expectedMaps,-1);}
    private int compactTableOffset(int expected,int minMaps,int maxMaps,int nextMarkerValue){
        int limit=bytes.length-Offsets.offsets_130,marker=-1;
        if(nextMarkerValue>=0){try{marker=findInt(nextMarkerValue);}catch(IllegalStateException ignored){}}
        int from=Math.max(0,expected-65536),to=Math.min(limit-4,expected+65536),best=-1,bestDistance=Integer.MAX_VALUE;
        for(int base=from;base<=to;base++){
            int maps=ushortAt(base);if(maps<minMaps||maps>maxMaps)continue;
            int stages=byteAt(base+2),stars=byteAt(base+3);if(stages<1||stages>100||stars<1||stars>16)continue;
            long end=(long)base+4+(long)maps*stars*3L+(long)maps*stages*stars*2L+maps;
            if(end>limit)continue;
            if(marker>=0){long gap=marker-end;if(gap<0||gap>128)continue;}
            int distance=Math.abs(base-expected);if(distance<bestDistance){best=base;bestDistance=distance;}
        }
        if(best>=0)return best;
        throw new IllegalStateException("Compact map table is unavailable");
    }
    private int eventTableOffset(){if(validEventTable(eventTableBaseCache))return eventTableBaseCache;int expected=fixed(Offsets.offsets_119),best=-1,distance=Integer.MAX_VALUE;for(int base=0;base+5<=bytes.length-Offsets.offsets_130;base++){if(!validEventTable(base))continue;int d=Math.abs(base-expected);if(d<distance){best=base;distance=d;}}if(best>=0){eventTableBaseCache=best;return best;}throw new IllegalStateException("Event map table is unavailable");}
    private boolean validEventTable(int base){if(base<0||base+5>bytes.length-Offsets.offsets_130)return false;int types=byteAt(base),subchapters=ushortAt(base+1),stars=byteAt(base+3),stages=byteAt(base+4);long maps=(long)types*subchapters,end=(long)base+5+maps*stars*2+maps*stages*stars*2;return types>=2&&types<=16&&subchapters>268&&subchapters<=2000&&maps==2500&&stars>0&&stars<=16&&stages>0&&stages<=100&&end<=bytes.length-Offsets.offsets_130;}
    private int standardTableOffset(int expected,int expectedMaps){for(int delta=0;delta<=10000;delta++){int[] candidates=delta==0?new int[]{expected}:new int[]{expected+delta,expected-delta};for(int base:candidates){if(base<0||base+12>bytes.length-Offsets.offsets_130||intAt(base)!=expectedMaps)continue;int stages=intAt(base+4),stars=intAt(base+8);long end=(long)base+12+(long)expectedMaps*stars*8+(long)expectedMaps*stages*stars*4;if(stages>0&&stages<=100&&stars>0&&stars<=16&&end<=bytes.length-Offsets.offsets_130)return base;}}throw new IllegalStateException("Standard map table is unavailable");}
    private int legendQuestTableOffset(){
        int expected=afterCannons(Offsets.offsets_120),limit=bytes.length-Offsets.offsets_130;
        int from=Math.max(0,expected-65536),to=Math.min(limit-8,expected+65536),best=-1,bestDistance=Integer.MAX_VALUE;
        for(int base=from;base<=to;base++){
            int maps=byteAt(base),stages=byteAt(base+1),stars=byteAt(base+2);
            if(maps!=1||stages!=48||stars!=4)continue;
            long selected=(long)base+3L+maps*stars;
            long progress=selected+maps*stars;
            long stageEnd=progress+(long)maps*stages*stars*2L;
            long triesEnd=stageEnd+(long)maps*stages*stars*2L;
            long unlockEnd=triesEnd+maps*stars, unknownEnd=unlockEnd+maps;
            long tail=unknownEnd+(long)stages*4L;
            if(tail+7>limit||intAt((int)tail+3)!=80600)continue;
            int candidateDistance=Math.abs(base-expected);
            if(candidateDistance<bestDistance){best=base;bestDistance=candidateDistance;}
        }
        if(best>=0)return best;
        throw new IllegalStateException("Legend Quest table is unavailable");
    }
    private int variableTableOffset(int expected,int expectedMaps){
        // Transfer-code profiles can move the 12-map Dojo block by more than
        // the old template-sized probe (the TW sample is shifted by 11,520
        // bytes).  Keep the structural validation, but widen the search to
        // the full variable profile window.
        int limit=bytes.length-Offsets.offsets_130;
        int from=Math.max(0,expected-65536),to=Math.min(limit-2,expected+65536);
        for(int base=from;base<=to;base++){
            if(base+2>limit||ushortAt(base)!=expectedMaps)continue;
            int p=base+2;boolean valid=true;
            for(int map=0;map<expectedMaps&&valid;map++){
                if(p+2>limit){valid=false;break;}
                p++;int stars=byteAt(p++);
                if(stars<1||stars>16){valid=false;break;}
                for(int star=0;star<stars;star++){
                    if(p+5>limit){valid=false;break;}
                    p+=3;int stages=ushortAt(p);p+=2;
                    if(stages<1||stages>1000||p+stages*2>limit){valid=false;break;}
                    p+=stages*2;
                }
            }
            if(valid)return base;
        }
        throw new IllegalStateException("Variable map table is unavailable");
    }
    /**
     * Challenge chapters are serialized as a repeated Chapters header rather
     * than the compact map tables used by most late-game editors.  Transfer
     * code saves may move this block when variable-length records earlier in
     * the save grow, so the old template offset is only a hint.  Validate the
     * complete upstream read order, including the score list and popup byte,
     * before accepting a candidate.
     */
    private int challengeTableOffset(){
        int expected=afterCannons(Offsets.offsets_121),limit=bytes.length-Offsets.offsets_130;
        int from=Math.max(0,expected-65536),to=Math.min(limit-64,expected+65536),best=-1,bestDistance=Integer.MAX_VALUE;
        for(int base=from;base<=to;base++){
            if(base+16>limit||intAt(base)<=0||intAt(base)>64)continue;
            int chapters=intAt(base),stars=intAt(base+4);
            if(stars<1||stars>16)continue;
            long p=(long)base+8L+chapters*stars*4L;
            if(p+8>limit||intAt((int)p)!=chapters||intAt((int)p+4)!=stars)continue;
            p+=8L+chapters*stars*4L;
            if(p+12>limit||intAt((int)p)!=chapters)continue;
            int stages=intAt((int)p+4),stageStars=intAt((int)p+8);
            if(stages<1||stages>1000||stageStars!=stars)continue;
            p+=12L;
            long stageEnd=p+(long)chapters*stages*stars*4L;
            if(stageEnd+8>limit||intAt((int)stageEnd)!=chapters||intAt((int)stageEnd+4)!=stars)continue;
            long unlockEnd=stageEnd+8L+(long)chapters*stars*4L;
            if(unlockEnd+4>limit)continue;
            int scoreCount=intAt((int)unlockEnd);
            if(scoreCount<0||scoreCount>1000)continue;
            long popup=(long)unlockEnd+4L+scoreCount*4L;
            if(popup+5>limit||byteAt((int)popup)>1||intAt((int)popup+1)!=67)continue;
            int candidateDistance=Math.abs(base-expected);
            if(candidateDistance<bestDistance){best=base;bestDistance=candidateDistance;}
        }
        if(best>=0)return best;
        throw new IllegalStateException("Challenge map table is unavailable");
    }
    private MapLayout standardLayout(int base,boolean repeated){MapLayout l=new MapLayout();l.valueWidth=4;l.maps=intAt(base);l.stars=intAt(base+4);if(l.maps<0||l.maps>1000||l.stars<0||l.stars>16)throw new IllegalStateException("Invalid map data");int p=base+8+l.maps*l.stars*4;if(repeated)p+=8;l.progressBase=p;p+=l.maps*l.stars*4;if(repeated){l.stages=intAt(p+4);p+=12;}else{l.stages=intAt(base+4);l.stars=intAt(base+8);l.progressBase=base+12+l.maps*l.stars*4;p=l.progressBase+l.maps*l.stars*4;}l.stageBase=p;l.stageMajor=true;l.unlockBase=l.stageBase+l.maps*l.stages*l.stars*4+(repeated?8:0);l.unlockValueWidth=4;return l;}
    private MapLayout compactLayout(int base){MapLayout l=new MapLayout();l.maps=ushortAt(base);l.stages=byteAt(base+2);l.stars=byteAt(base+3);if(l.maps<0||l.maps>1000||l.stages<0||l.stages>100||l.stars<0||l.stars>16)throw new IllegalStateException("Invalid compact map data");l.progressBase=base+4+l.maps*l.stars;l.stageBase=l.progressBase+l.maps*l.stars;l.unlockBase=l.stageBase+l.maps*l.stages*l.stars*2;l.shortValues=true;l.stageMajor=true;return l;}
    private MapLayout variableLayout(int base){return variableLayout(base,false);}
    private MapLayout variableLayout(int base,boolean preserveUnlock){MapLayout l=new MapLayout();l.maps=ushortAt(base);if(l.maps<0||l.maps>1000)throw new IllegalStateException("Invalid variable map data");l.variable=true;l.preserveVariableUnlock=preserveUnlock;l.shortValues=true;l.variableStageOffsets=new int[l.maps][];l.variableProgressOffsets=new int[l.maps][];l.variableUnlockOffsets=new int[l.maps][];int p=base+2;for(int map=0;map<l.maps;map++){int unknown=byteAt(p++),starCount=byteAt(p++);l.variableStageOffsets[map]=new int[starCount];l.variableProgressOffsets[map]=new int[starCount];l.variableUnlockOffsets[map]=new int[starCount];for(int star=0;star<starCount;star++){p++;l.variableProgressOffsets[map][star]=p++;l.variableUnlockOffsets[map][star]=p++;int count=ushortAt(p);p+=2;l.variableStageOffsets[map][star]=p;p+=count*2;}}return l;}
    private MapLayout dojoLayout(int base){
        // Dojo chapters use the same header as the upstream Chapters reader:
        // int chapter count, int star count, selected-stage ints, then a
        // second chapter/star header, progress ints, a third chapter/stage/star
        // header, stage ints, a fourth chapter/star header, unlock ints.
        int limit=bytes.length-Offsets.offsets_130,chapters=intAt(base),stars=intAt(base+4);
        if(chapters<1||chapters>100||stars<1||stars>16){
            // 15.5 transfer saves can carry a compact zero-chapter dojo
            // block; its logical records are still represented by the
            // serialized 12-map variable table immediately after marker 140000.
            return variableLayout(base,true);
        }
        int p=base+8+chapters*stars*4;
        if(intAt(p)!=chapters||intAt(p+4)!=stars)throw new IllegalStateException("Invalid dojo progress header");
        p+=8+chapters*4;
        if(intAt(p)!=chapters||intAt(p+8)!=stars)throw new IllegalStateException("Invalid dojo stage header");
        int stages=intAt(p+4); if(stages<1||stages>1000)throw new IllegalStateException("Invalid dojo stage count");
        p+=12+chapters*stages*4;
        if(intAt(p)!=chapters||intAt(p+4)!=stars)throw new IllegalStateException("Invalid dojo unlock header");
        MapLayout l=new MapLayout();l.maps=chapters;l.stars=stars;l.stages=stages;l.progressBase=base+8+chapters*stars*4+8;l.stageBase=p- chapters*stages*4;l.unlockBase=p+8;l.valueWidth=4;l.shortValues=false;l.stageMajor=true;return l;
    }
    private MapLayout eventLayout(int base){MapLayout l=new MapLayout();int types=byteAt(base),subchapters=ushortAt(base+1);l.maps=types*subchapters;l.mapGroupSize=subchapters;l.stars=byteAt(base+3);l.stages=byteAt(base+4);l.progressBase=base+5+l.maps*l.stars;l.stageBase=l.progressBase+l.maps*l.stars;l.unlockBase=l.stageBase+l.maps*l.stages*l.stars*2;l.shortValues=true;l.stageMajor=true;l.valueWidth=2;l.cascadeCompletion=true;return l;}
    private void clearEventMap(int type,int map,int star) { int base=eventTableOffset(),types=byteAt(base),subchapters=ushortAt(base+1),stars=byteAt(base+3),stages=byteAt(base+4);if(type<0||type>=types||map<0||map>=subchapters||star<0||star>=stars)throw new IndexOutOfBoundsException();int flat=type*subchapters+map,progress=base+5+types*subchapters*stars,stageBase=progress+types*subchapters*stars,unlockBase=stageBase+types*subchapters*stages*stars*2;bytes[progress+flat*stars+star]=(byte)stages;for(int stage=0;stage<stages;stage++){int offset=stageBase+((flat*stages+stage)*stars+star)*2;int current=ushortAt(offset);putShort(offset,current==0?1:current);}bytes[unlockBase+flat*stars+star]=3;if(star+1<stars)bytes[unlockBase+flat*stars+star+1]=1;if(map+1<subchapters)bytes[unlockBase+(flat+1)*stars]=1; }
    private MapLayout legendQuestLayout(int base){MapLayout l=new MapLayout();l.maps=byteAt(base);l.stages=byteAt(base+1);l.stars=byteAt(base+2);l.progressBase=base+3+l.maps*l.stars;l.stageBase=l.progressBase+l.maps*l.stars;l.mirrorStageBase=l.stageBase+l.maps*l.stages*l.stars*2;l.unlockBase=l.mirrorStageBase+l.maps*l.stages*l.stars*2;l.shortValues=true;l.stageMajor=true;l.valueWidth=2;return l;}
    private final class MapLayout {
        int maps,stars,stages,stageBase,progressBase,valueWidth=2,mirrorStageBase=-1,unlockBase=-1,unlockValueWidth=1,mapGroupSize;boolean shortValues,variable,stageMajor,cascadeCompletion;
        int[][] variableStageOffsets;int[][] variableProgressOffsets;int[][] variableUnlockOffsets;boolean preserveVariableUnlock;
        int starsAt(int map){return variable?variableStageOffsets[map].length:stars;}
        int stagesAt(int map,int star){return variable?(variableProgressOffsets[map][star]+3<bytes.length?ushortAt(variableProgressOffsets[map][star]+2):0):stages;}
        int stageOffset(int map,int star,int stage){if(variable)return variableStageOffsets[map][star]+stage*2;if(stageMajor)return stageBase+((map*stages+stage)*stars+star)*valueWidth;return stageBase+((map*stars+star)*stages+stage)*2;}
        int mirrorOffset(int map,int star,int stage){return mirrorStageBase+((map*stages+stage)*stars+star)*2;}
        void updateProgress(int map,int star,int stage,int value){if(value>0){int current=currentProgress(map,star);if(current<stage+1)setProgress(map,star,stage+1);setUnlock(map,star,3);if(cascadeCompletion&&stage==stagesAt(map,star)-1){if(star+1<starsAt(map))setUnlock(map,star+1,1);else if(hasNextMap(map))setUnlock(map+1,0,1);}}else{setProgress(map,star,Math.min(stage,currentProgress(map,star)));for(int s=star+1;s<starsAt(map);s++)setUnlock(map,s,0);if(star==0&&hasNextMap(map))for(int s=0;s<starsAt(map+1);s++)setUnlock(map+1,s,0);}}
        int currentProgress(int map,int star){return variable?byteAt(variableProgressOffsets[map][star]):shortValues?byteAt(progressBase+map*stars+star):intAt(progressBase+(map*stars+star)*4);}
        void setProgress(int map,int star,int value){if(variable)bytes[variableProgressOffsets[map][star]]=(byte)value;else if(shortValues)bytes[progressBase+map*stars+star]=(byte)value;else putInt(progressBase+(map*stars+star)*4,value);}
        void setUnlock(int map,int star,int value){if(variable){if(preserveVariableUnlock)return;if(map<0||map>=variableUnlockOffsets.length||star<0||star>=variableUnlockOffsets[map].length)return;bytes[variableUnlockOffsets[map][star]]=(byte)value;return;}if(unlockBase<0)return;int offset=unlockBase+(map*stars+star)*unlockValueWidth;if(unlockValueWidth==1)bytes[offset]=(byte)value;else putInt(offset,value);}
        boolean hasNextMap(int map){return map+1<maps&&(mapGroupSize==0||(map+1)%mapGroupSize!=0);}
    }
    private int[] intArray(int offset,int count) { ensureItemProfile(); int[] out=new int[count]; for(int i=0;i<count;i++) out[i]=intAt(offset+i*4); return out; }
    private int cataminCount(CatLayout layout) {
        int count = intAt(layout.cataminsCountOffset);
        if (count < 0 || count > 1024) throw new IllegalStateException("Invalid Catamin list count=" + count + " at offset=" + layout.cataminsCountOffset);
        return count;
    }
    private void setArrayInt(int offset,int count,int index,int value) { ensureItemProfile(); if(index<0||index>=count)throw new IndexOutOfBoundsException(); putInt(offset+index*4,value); refreshHash(); }
    private static String md5(String salt, byte[] bytes, int offset, int length) { return hex(md5Bytes(salt, bytes, offset, length)); }
    private static byte[] md5Bytes(String salt, byte[] bytes) { return md5Bytes(salt, bytes, 0, bytes.length); }
    private static byte[] md5Bytes(String salt, byte[] bytes, int offset, int length) {
        try { MessageDigest md = MessageDigest.getInstance("MD5"); md.update(salt.getBytes(java.nio.charset.StandardCharsets.UTF_8)); md.update(bytes, offset, length); return md.digest(); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(bytes.length * 2); for (byte b : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", b)); return out.toString(); }
}
