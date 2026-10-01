package io.github.tuxkoh.bcsfe;

import org.junit.After;
import org.junit.Assume;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import io.github.tuxkoh.bcsfe.core.SaveDocument;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.*;

public final class TransferClientTest {
    @After public void resetEndpoints(){TransferClient.setEndpointsForTests(null);}

    @Test public void accountSignatureMatchesUpstreamFixedVector() throws Exception {
        String nonce="0".repeat(64);
        assertEquals("00000000000000000000000000000000000000000000000000000000000000009218b68ae72b3d8aded9459643b12c7647a081e41b7feec3eaebf2f96ca9e907",TransferClient.signatureWithRandom("123456789","{}",false,nonce));
    }

    @Test public void transferMetadataSignatureRepeatsPayloadLikeUpstream() throws Exception {
        String nonce="a".repeat(40);
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaae5df763de4179619e8153e42016b7cd6b131694b",TransferClient.signatureWithRandom("123456789","[]",true,nonce));
    }

    @Test(expected=IllegalArgumentException.class) public void signatureRejectsWrongNonceLength() throws Exception {
        TransferClient.signatureWithRandom("123456789","{}",false,"short");
    }

    @Test public void safeErrorsNeverExposeExceptionMessages(){assertEquals("IllegalStateException",TransferClient.safeError(new IllegalStateException("transfer=SECRET pin=1234 token=PRIVATE")));}

    @Test public void receiveUsesUpstreamRequestShapeAndReturnsCredentials() throws Exception {
        byte[] save=fixture();
        try(MockWebServer server=server()){
            server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type","application/octet-stream").setHeader("Nyanko-Password-Refresh-Token","refresh-from-server").setHeader("Nyanko-Password","password-from-server").setBody(new okio.Buffer().write(save)));
            TransferClient.ReceivedSave result=TransferClient.receive("ABC 123","1234","tw");
            RecordedRequest request=take(server);String body=request.getBody().readUtf8();
            assertEquals("/v2/transfers/ABC+123/reception",request.getPath());assertTrue(body.contains("\"countryCode\":\"tw\""));assertTrue(body.contains("\"version\":150600"));assertTrue(body.contains("\"pin\":\"1234\""));
            assertArrayEquals(save,result.data);assertEquals("refresh-from-server",result.passwordRefreshToken);assertEquals("password-from-server",result.password);
        }
    }

    @Test public void receiveClientVersionsFollowOfficialRegionalReleases() {
        assertEquals(150600,TransferClient.latestTransferVersion("jp"));
        assertEquals(150600,TransferClient.latestTransferVersion("tw"));
        assertEquals(150600,TransferClient.latestTransferVersion("en"));
        assertEquals(150600,TransferClient.latestTransferVersion("kr"));
    }

    @Test public void failedAuthenticationDoesNotPartiallyMutateDocumentCredentials() throws Exception {
        SaveDocument document=SaveDocument.open(fixture());byte[] before=document.toBytes();
        try(MockWebServer server=server()){
            server.enqueue(json(200,"{\"statusCode\":1,\"timestamp\":1700000001,\"payload\":{\"accountCode\":\"987654321\",\"passwordRefreshToken\":\"0123456789012345678901234567890123456789\",\"password\":\"new-password\"}}"));
            server.enqueue(json(500,"{\"statusCode\":0}"));
            try{TransferClient.uploadManagedItems(document,null);fail("expected failure");}catch(Exception expected){assertTrue(TransferClient.safeError(expected).contains("auth-token"));}
            assertArrayEquals(before,document.toBytes());assertEquals("/v1/user/password",take(server).getPath());assertEquals("/v1/tokens",take(server).getPath());
        }
    }

    @Test public void managedItemUploadCommitsRefreshedCredentialsOnlyInResult() throws Exception {
        SaveDocument document=SaveDocument.open(fixture());byte[] before=document.toBytes();
        try(MockWebServer server=server()){
            server.enqueue(json(200,"{\"statusCode\":1,\"timestamp\":1700000001,\"payload\":{\"passwordRefreshToken\":\"0123456789012345678901234567890123456789\",\"password\":\"new-password\"}}"));
            server.enqueue(json(200,"{\"statusCode\":1,\"payload\":{\"token\":\"auth-token\"}}"));
            server.enqueue(json(200,"{\"statusCode\":1,\"payload\":{}}"));
            TransferClient.ManagedUploadResult result=TransferClient.uploadManagedItems(document,null);
            assertEquals("new-password",result.password);assertArrayEquals(before,document.toBytes());
            SaveDocument updated=SaveDocument.open(result.updatedSave);assertEquals("0123456789012345678901234567890123456789",updated.passwordRefreshToken());
            take(server);take(server);RecordedRequest managed=take(server);String body=managed.getBody().readUtf8();
            assertEquals("/v1/managed-items",managed.getPath());assertEquals("Bearer auth-token",managed.getHeader("Authorization"));assertTrue(body.contains("\"catfoodAmount\":"+document.catFood()));assertTrue(body.contains("\"isPaid\":true"));
        }
    }

    @Test public void fullUploadCoversAuthenticationSaveKeyMultipartAndTransferCodes() throws Exception {
        SaveDocument document=SaveDocument.open(fixture());byte[] before=document.toBytes();
        try(MockWebServer server=server()){
            server.enqueue(json(200,"{\"statusCode\":1,\"payload\":{\"passwordRefreshToken\":\"0123456789012345678901234567890123456789\",\"password\":\"new-password\"}}"));
            server.enqueue(json(200,"{\"statusCode\":1,\"payload\":{\"token\":\"auth-token\"}}"));
            server.enqueue(json(200,"{\"statusCode\":1,\"payload\":{\"url\":\""+server.url("/s3")+"\",\"key\":\"save-key\",\"policy\":\"policy\"}}"));
            server.enqueue(new MockResponse().setResponseCode(204));
            server.enqueue(json(200,"{\"statusCode\":1,\"payload\":{\"transferCode\":\"TRANSFER\",\"pin\":\"4321\"}}"));
            TransferClient.UploadResult result=TransferClient.upload(document,null);
            assertEquals("TRANSFER",result.transferCode);assertEquals("4321",result.pin);assertEquals("new-password",result.password);assertArrayEquals(before,document.toBytes());assertTrue(SaveDocument.open(result.updatedSave).checksumValid());
            take(server);take(server);RecordedRequest key=take(server),multipart=take(server),transfer=take(server);
            assertTrue(key.getPath().startsWith("/v2/save/key?nonce="));assertEquals("/s3",multipart.getPath());assertTrue(multipart.getHeader("Content-Type").startsWith("multipart/form-data"));String metadata=transfer.getBody().readUtf8();assertEquals("/v2/transfers",transfer.getPath());assertTrue(metadata.contains("\"saveKey\":\"save-key\""));assertTrue(metadata.contains("\"managedItemDetails\":[]"));
        }
    }

    @Test public void replacementAccountFlowMatchesUpstreamAndUpdatesAllCredentials() throws Exception {
        SaveDocument document=SaveDocument.open(fixture());
        try(MockWebServer server=server()){
            server.enqueue(json(200,"{\"accountId\":\"987654321\"}"));
            server.enqueue(json(200,"{\"statusCode\":1,\"timestamp\":1700000002,\"payload\":{\"accountCode\":\"987654321\",\"passwordRefreshToken\":\"9876543210987654321098765432109876543210\",\"password\":\"replacement-password\"}}"));
            server.enqueue(json(200,"{\"statusCode\":1,\"payload\":{\"token\":\"replacement-token\"}}"));
            server.enqueue(json(200,"{\"statusCode\":1,\"payload\":{}}"));
            server.enqueue(json(200,"{\"statusCode\":1,\"payload\":{\"key\":\"replacement-save-key\"}}"));
            TransferClient.AccountResult result=TransferClient.createNewAccount(document);
            assertEquals("replacement-password",result.password);assertEquals("987654321",document.inquiryCode());assertEquals("9876543210987654321098765432109876543210",document.passwordRefreshToken());assertEquals(1700000002L,document.accountCreatedAt());assertFalse(document.showBanMessage());assertTrue(document.checksumValid());
            RecordedRequest create=take(server),password=take(server),token=take(server),managed=take(server),key=take(server);
            assertTrue(create.getPath().startsWith("/?action=createAccount&referenceId="));assertEquals("/v1/users",password.getPath());assertTrue(password.getBody().readUtf8().contains("\"accountCode\":\"987654321\""));assertEquals("/v1/tokens",token.getPath());assertEquals("/v1/managed-items",managed.getPath());assertTrue(key.getPath().startsWith("/v2/save/key?nonce="));
        }
    }

    @Test public void replacementUploadRetriesWithoutMutatingSource() throws Exception {
        Path template=Path.of("src/main/assets/new_saves/tw.save");
        SaveDocument document=SaveDocument.open(Files.readAllBytes(template));byte[] before=document.toBytes();
        try(MockWebServer server=server()){
            server.enqueue(json(500,"{}"));
            server.enqueue(json(200,"{\"accountId\":\"987654321\"}"));
            server.enqueue(json(200,"{\"statusCode\":1,\"timestamp\":1700000002,\"payload\":{\"accountCode\":\"987654321\",\"passwordRefreshToken\":\"9876543210987654321098765432109876543210\",\"password\":\"replacement-password\"}}"));
            server.enqueue(json(200,"{\"statusCode\":1,\"payload\":{\"token\":\"replacement-token\"}}"));
            server.enqueue(json(200,"{\"statusCode\":1,\"payload\":{}}"));
            server.enqueue(json(200,"{\"statusCode\":1,\"payload\":{\"key\":\"replacement-save-key\"}}"));
            server.enqueue(json(200,"{\"statusCode\":1,\"payload\":{\"token\":\"upload-token\"}}"));
            server.enqueue(json(200,"{\"statusCode\":1,\"payload\":{\"url\":\""+server.url("/s3")+"\",\"key\":\"save-key\",\"policy\":\"policy\"}}"));
            server.enqueue(new MockResponse().setResponseCode(204));
            server.enqueue(json(200,"{\"statusCode\":1,\"payload\":{\"transferCode\":\"TRANSFER\",\"pin\":\"4321\"}}"));
            TransferClient.UploadResult result=TransferClient.uploadWithReplacementAccount(document);
            assertEquals("TRANSFER",result.transferCode);assertEquals("4321",result.pin);assertArrayEquals(before,document.toBytes());
            assertEquals("/?action=createAccount&referenceId=",take(server).getPath());
            assertEquals("/?action=createAccount&referenceId=",take(server).getPath());
        }
    }

    @Test public void rootLoadCreatesReplacementBeforeWritingAndPreservesAllOtherBytes() throws Exception {
        SaveDocument original=SaveDocument.open(Files.readAllBytes(Path.of("src/main/assets/new_saves/tw.save")));
        original.setInquiryCode("123456789");
        original.setPasswordRefreshToken("1111111111111111111111111111111111111111");
        original.setShowBanMessage(true);
        original.setCatFood(321);
        original.setRareTickets(28);
        byte[] source=original.toBytes(),before=source.clone();
        try(MockWebServer server=server()){
            enqueueReplacementAccount(server);
            java.util.concurrent.atomic.AtomicInteger writes=new java.util.concurrent.atomic.AtomicInteger();
            TransferClient.ReplacementSave result=RootSaveLoader.load(source,SaveDocument.Region.TW,(region,save)->{
                assertEquals(5,server.getRequestCount());
                assertEquals(SaveDocument.Region.TW,region);
                SaveDocument written=SaveDocument.open(save);
                assertEquals("987654321",written.inquiryCode());
                assertEquals("9876543210987654321098765432109876543210",written.passwordRefreshToken());
                assertTrue(written.checksumValid());
                writes.incrementAndGet();
            });
            assertEquals(1,writes.get());
            assertEquals("replacement-password",result.password);
            assertEquals("987654321",result.document.inquiryCode());
            assertEquals("9876543210987654321098765432109876543210",result.document.passwordRefreshToken());
            assertEquals(1700000002L,result.document.accountCreatedAt());
            assertFalse(result.document.showBanMessage());
            assertTrue(result.document.checksumValid());
            assertArrayEquals(before,source);
            assertArrayEquals(before,original.toBytes());
            SaveDocument expected=SaveDocument.open(before);
            expected.setInquiryCode("987654321");
            expected.setPasswordRefreshToken("9876543210987654321098765432109876543210");
            expected.setAccountCreatedAt(1700000002L);
            expected.setShowBanMessage(false);
            assertArrayEquals(expected.toBytes(),result.document.toBytes());
            assertEquals(5,server.getRequestCount());
            for(int i=0;i<5;i++){
                String body=take(server).getBody().readUtf8();
                assertFalse(body.contains("123456789"));
                assertFalse(body.contains("1111111111111111111111111111111111111111"));
            }
        }
    }

    @Test public void rootLoadAccountFailureAtEveryRequiredNetworkStageNeverWritesGame() throws Exception {
        byte[] source=Files.readAllBytes(Path.of("src/main/assets/new_saves/tw.save")),before=source.clone();
        // Managed-item synchronization is already optional in createNewAccount.
        for(int failedStage:new int[]{0,1,2,4}){
            try(MockWebServer server=server()){
                MockResponse[] responses=replacementResponses();
                for(int i=0;i<failedStage;i++)server.enqueue(responses[i]);
                server.enqueue(json(503,"{\"statusCode\":0}"));
                assertThrows(RootSaveLoader.AccountCreationFailure.class,()->RootSaveLoader.load(source,null,(region,data)->fail("Game writer must not be called on account failure")));
                assertArrayEquals(before,source);
                assertEquals(failedStage+1,server.getRequestCount());
            }
        }
    }

    @Test public void rootLoadRejectsInvalidSaveOrWrongRegionBeforeCreatingAccount() throws Exception {
        byte[] source=Files.readAllBytes(Path.of("src/main/assets/new_saves/tw.save")),before=source.clone();
        try(MockWebServer server=server()){
            assertThrows(IllegalArgumentException.class,()->RootSaveLoader.load(source,SaveDocument.Region.JP,(region,data)->fail("No writes on wrong region")));
            source[0]^=1;
            byte[] damaged=source.clone();
            assertThrows(IllegalArgumentException.class,()->RootSaveLoader.load(source,null,(region,data)->fail("No writes on parse failure")));
            assertArrayEquals(damaged,source);
            assertEquals(0,server.getRequestCount());
        }
        before[0]^=1;
        assertArrayEquals(before,source);
    }

    @Test public void rootLoadRejectsUnknownVersionBeforeCreatingAccount() throws Exception {
        byte[] source=Files.readAllBytes(Path.of("src/main/assets/new_saves/tw.save"));
        SaveDocument template=SaveDocument.open(source);
        java.lang.reflect.Field field=SaveDocument.class.getDeclaredField("gameVersionOffset");
        field.setAccessible(true);
        java.nio.ByteBuffer.wrap(source).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(field.getInt(template),150601);
        java.security.MessageDigest md5=java.security.MessageDigest.getInstance("MD5");
        md5.update("battlecatstw".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        md5.update(source,0,source.length-32);
        byte[] hash=java.util.HexFormat.of().formatHex(md5.digest()).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(hash,0,source,source.length-32,32);
        assertTrue(SaveDocument.open(source).checksumValid());
        byte[] before=source.clone();
        try(MockWebServer server=server()){
            assertThrows(UnsupportedOperationException.class,()->RootSaveLoader.load(source,null,(region,data)->fail("No writes on unknown layout")));
            assertArrayEquals(before,source);
            assertEquals(0,server.getRequestCount());
        }
    }

    @Test public void rootLoadWriteFailureDoesNotReturnSuccessOrMutateWorkingSave() throws Exception {
        byte[] source=Files.readAllBytes(Path.of("src/main/assets/new_saves/tw.save")),before=source.clone();
        try(MockWebServer server=server()){
            enqueueReplacementAccount(server);
            java.io.IOException failure=new java.io.IOException("Test writer failure");
            assertSame(failure,assertThrows(java.io.IOException.class,()->RootSaveLoader.load(source,null,(region,data)->{throw failure;})));
            assertArrayEquals(before,source);
        }
    }

    private static MockResponse[] replacementResponses(){return new MockResponse[]{
            json(200,"{\"accountId\":\"987654321\"}"),
            json(200,"{\"statusCode\":1,\"timestamp\":1700000002,\"payload\":{\"accountCode\":\"987654321\",\"passwordRefreshToken\":\"9876543210987654321098765432109876543210\",\"password\":\"replacement-password\"}}"),
            json(200,"{\"statusCode\":1,\"payload\":{\"token\":\"replacement-token\"}}"),
            json(200,"{\"statusCode\":1,\"payload\":{}}"),
            json(200,"{\"statusCode\":1,\"payload\":{\"key\":\"replacement-save-key\"}}")};}

    private static void enqueueReplacementAccount(MockWebServer server){for(MockResponse response:replacementResponses())server.enqueue(response);}

    private static byte[] fixture() throws Exception {Path path=Path.of("/tmp/bcsfe-transfer-tw.save");Assume.assumeTrue(Files.isRegularFile(path));return Files.readAllBytes(path);}
    private static MockWebServer server()throws Exception{MockWebServer server=new MockWebServer();server.start();String base=server.url("/").toString();TransferClient.setEndpointsForTests(new TransferClient.Endpoints(base,base,base,base));return server;}
    private static MockResponse json(int status,String body){return new MockResponse().setResponseCode(status).setHeader("Content-Type","application/json").setBody(body);}
    private static RecordedRequest take(MockWebServer server)throws Exception{RecordedRequest request=server.takeRequest(2,TimeUnit.SECONDS);assertNotNull(request);return request;}
}
