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
        TransferClient.ReplacementSave replacement;
        try {
            replacement=TransferClient.createReplacementCopy(source,region);
        } catch(UnsupportedOperationException | IllegalArgumentException unsupported){
            throw unsupported;
        } catch(Exception failed){
            throw new AccountCreationFailure(failed);
        }
        writer.write(replacement.document.region(),replacement.document.toBytes());
        return replacement;
    }
}
