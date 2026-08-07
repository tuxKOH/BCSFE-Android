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
            assertEquals("/v2/transfers/ABC+123/reception",request.getPath());assertTrue(body.contains("\"countryCode\":\"tw\""));assertTrue(body.contains("\"version\":150500"));assertTrue(body.contains("\"pin\":\"1234\""));
            assertArrayEquals(save,result.data);assertEquals("refresh-from-server",result.passwordRefreshToken);assertEquals("password-from-server",result.password);
        }
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

    private static byte[] fixture() throws Exception {Path path=Path.of("/tmp/bcsfe-transfer-tw.save");Assume.assumeTrue(Files.isRegularFile(path));return Files.readAllBytes(path);}
    private static MockWebServer server()throws Exception{MockWebServer server=new MockWebServer();server.start();String base=server.url("/").toString();TransferClient.setEndpointsForTests(new TransferClient.Endpoints(base,base,base,base));return server;}
    private static MockResponse json(int status,String body){return new MockResponse().setResponseCode(status).setHeader("Content-Type","application/json").setBody(body);}
    private static RecordedRequest take(MockWebServer server)throws Exception{RecordedRequest request=server.takeRequest(2,TimeUnit.SECONDS);assertNotNull(request);return request;}
}
