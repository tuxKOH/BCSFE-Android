package io.github.tuxkoh.bcsfe;

import io.github.tuxkoh.bcsfe.core.SaveDocument;

/** Account preparation must finish before the installed game's files are touched. */
final class RootSaveLoader {
    private RootSaveLoader() {}

    interface SaveWriter {
        void write(SaveDocument.Region region,byte[] save) throws Exception;
    }

    static final class AccountCreationFailure extends Exception {
        AccountCreationFailure(Exception cause){super("Replacement account creation failed",cause);}
    }

    static TransferClient.ReplacementSave load(byte[] source,SaveDocument.Region region,SaveWriter writer) throws Exception {
        return load(source,region,null,true,writer);
    }

    static TransferClient.ReplacementSave load(byte[] source,SaveDocument.Region region,String password,
                                               boolean createReplacement,SaveWriter writer) throws Exception {
        TransferClient.ReplacementSave prepared=prepareCopy(source,region,password,createReplacement);
        if(!prepared.document.isOfficiallySupportedVersion()||!prepared.document.hasItemProfile())
            throw new UnsupportedOperationException("Root load requires a supported save layout");
        writer.write(prepared.document.region(),prepared.document.toBytes());
        return prepared;
    }

    static TransferClient.ReplacementSave prepareCopy(byte[] source,SaveDocument.Region region,String password,
                                                       boolean createReplacement) throws Exception {
        if(!createReplacement){
            SaveDocument copy=region==null?SaveDocument.open(source):SaveDocument.open(source,region);
            return new TransferClient.ReplacementSave(copy,password);
        }
        try {
            return TransferClient.createReplacementCopy(source,region);
        } catch(UnsupportedOperationException | IllegalArgumentException unsupported){
            throw unsupported;
        } catch(Exception failed){
            throw new AccountCreationFailure(failed);
        }
    }
}
