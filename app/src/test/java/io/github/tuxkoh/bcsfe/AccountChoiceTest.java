package io.github.tuxkoh.bcsfe;

import io.github.tuxkoh.bcsfe.core.SaveDocument;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public class AccountChoiceTest {
    @After public void reset(){TransferClient.setEndpointsForTests(null);}
    private static byte[] template()throws Exception{return Files.readAllBytes(Path.of("src/main/assets/new_saves/tw.save"));}
    private static MockResponse json(String body){return new MockResponse().setHeader("Content-Type","application/json").setBody(body);}
    private static void endpoints(MockWebServer server){String url=server.url("/").toString();TransferClient.setEndpointsForTests(new TransferClient.Endpoints(url,url,url,url));}

    @Test public void rootImportWithoutReplacementPreservesSourceAndMakesNoRequests()throws Exception{
        byte[] source=template(),before=source.clone();
        try(MockWebServer server=new MockWebServer()){
            server.start();endpoints(server);
            TransferClient.ReplacementSave imported=RootSaveLoader.prepareCopy(source,SaveDocument.Region.TW,null,false);
            assertEquals(0,server.getRequestCount());assertNull(imported.password);
            assertArrayEquals(before,source);assertArrayEquals(before,imported.document.toBytes());
            imported.document.setNormalTickets(7);
            assertArrayEquals(before,source);
        }
    }

    @Test public void rootLoadWithoutReplacementPreservesBytesPasswordAndMakesNoRequests()throws Exception{
        byte[] source=template(),before=source.clone();
        try(MockWebServer server=new MockWebServer()){
            server.start();endpoints(server);server.enqueue(new MockResponse().setResponseCode(503));
            final int[] writes={0};
            TransferClient.ReplacementSave result=RootSaveLoader.load(source,SaveDocument.Region.TW,"saved-password",false,(region,data)->{
                assertEquals(SaveDocument.Region.TW,region);assertArrayEquals(before,data);writes[0]++;
            });
            assertEquals(1,writes[0]);assertEquals(0,server.getRequestCount());
            assertArrayEquals(before,source);assertArrayEquals(before,result.document.toBytes());
            assertEquals("saved-password",result.password);
        }
    }

    @Test public void uncheckedUploadUsesOriginalAccountWithoutCreatingAnother()throws Exception{
        SaveDocument document=SaveDocument.open(template());byte[] before=document.toBytes();
        try(MockWebServer server=new MockWebServer()){
            server.start();endpoints(server);
            server.enqueue(json("{\"statusCode\":1,\"payload\":{\"token\":\"auth-token\"}}"));
            server.enqueue(json("{\"statusCode\":1,\"payload\":{\"url\":\""+server.url("/s3")+"\",\"key\":\"save-key\",\"policy\":\"policy\"}}"));
            server.enqueue(new MockResponse().setResponseCode(204));
            server.enqueue(json("{\"statusCode\":1,\"payload\":{\"transferCode\":\"TRANSFER\",\"pin\":\"4321\"}}"));
            TransferClient.UploadResult result=TransferClient.upload(document,"saved-password",false);
            assertEquals("saved-password",result.password);assertArrayEquals(before,document.toBytes());
            assertEquals(document.inquiryCode(),SaveDocument.open(result.updatedSave).inquiryCode());
            assertEquals("/v1/tokens",server.takeRequest().getPath());
            assertTrue(server.takeRequest().getPath().startsWith("/v2/save/key?"));
            assertEquals("/s3",server.takeRequest().getPath());assertEquals("/v2/transfers",server.takeRequest().getPath());
            assertEquals(4,server.getRequestCount());
        }
    }

    @Test public void uncheckedUploadFailureNeverFallsBackToReplacement()throws Exception{
        SaveDocument document=SaveDocument.open(template());byte[] before=document.toBytes();
        try(MockWebServer server=new MockWebServer()){
            server.start();endpoints(server);server.enqueue(new MockResponse().setResponseCode(503));
            server.enqueue(new MockResponse().setResponseCode(503));
            assertThrows(Exception.class,()->TransferClient.upload(document,null,false));
            assertEquals(2,server.getRequestCount());assertEquals("/v1/user/password",server.takeRequest().getPath());
            assertEquals("/v1/users",server.takeRequest().getPath());
            assertArrayEquals(before,document.toBytes());
        }
    }
}
