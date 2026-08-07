package io.github.tuxkoh.bcsfe;

import org.json.JSONObject;
import org.json.JSONArray;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.util.concurrent.TimeUnit;

final class TransferClient {
    private TransferClient() {}
    private static final SecureRandom SECURE_RANDOM=new SecureRandom();
    private static final String USER_AGENT="Dalvik/2.1.0 (Linux; U; Android 9; SM-G955F Build/N2G48B)";
    private static volatile Endpoints endpoints=Endpoints.production();
    static final class Endpoints {
        final String auth;
        final String save;
        final String backups;
        final String managed;
        Endpoints(String auth,String save,String backups,String managed){this.auth=trim(auth);this.save=trim(save);this.backups=trim(backups);this.managed=trim(managed);}
        private static String trim(String value){return value.endsWith("/")?value.substring(0,value.length()-1):value;}
        static Endpoints production(){return new Endpoints("https://nyanko-auth.ponosgames.com","https://nyanko-save.ponosgames.com","https://nyanko-backups.ponosgames.com","https://nyanko-managed-item.ponosgames.com");}
    }
    static void setEndpointsForTests(Endpoints value){endpoints=value==null?Endpoints.production():value;}
    private static final class NetworkFailure extends Exception {
        final String stage;
        final int status;
        final String responseType;
        NetworkFailure(String stage,int status,String responseType) { super(stage);this.stage=stage;this.status=status;this.responseType=responseType==null?"":responseType; }
    }
    static String safeError(Exception error) {
        if(error instanceof NetworkFailure failure)return failure.stage+" HTTP "+failure.status+(failure.responseType.isEmpty()?"":" "+failure.responseType);
        return error.getClass().getSimpleName();
    }
    static final class ReceivedSave {
        final byte[] data;
        final String passwordRefreshToken;
        final String password;

        ReceivedSave(byte[] data, String passwordRefreshToken, String password) {
            this.data = data;
            this.passwordRefreshToken = passwordRefreshToken;
            this.password = password;
        }
    }
    static final class UploadResult {
        final String transferCode;
        final String pin;
        final String password;
        final byte[] updatedSave;
        UploadResult(String transferCode,String pin,String password,byte[] updatedSave){this.transferCode=transferCode;this.pin=pin;this.password=password;this.updatedSave=updatedSave;}
    }
    static final class ManagedUploadResult {
        final String password;
        final byte[] updatedSave;
        ManagedUploadResult(String password,byte[] updatedSave){this.password=password;this.updatedSave=updatedSave;}
    }
    static final class AccountResult {
        final String password;
        AccountResult(String password){this.password=password;}
    }
    private static final class Authentication {
        final String token;
        final String password;
        Authentication(String token,String password){this.token=token;this.password=password;}
    }

    static ReceivedSave receive(String transfer, String pin, String region) throws Exception {
        String urlText = endpoints.save+"/v2/transfers/" +
                java.net.URLEncoder.encode(transfer, StandardCharsets.UTF_8.name()) + "/reception";
        JSONObject client = new JSONObject().put("clientInfo", new JSONObject()
                .put("client", new JSONObject().put("countryCode", region.equals("jp") ? "ja" : region).put("version", 150500))
                .put("device", new JSONObject().put("model", "SM-G955F"))
                .put("os", new JSONObject().put("type", "android").put("version", "9")))
                .put("nonce", randomHex(32)).put("pin", pin);
        HttpURLConnection connection = (HttpURLConnection) new URL(urlText).openConnection();
        connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
        connection.setRequestProperty("Content-Type", "application/json"); connection.setRequestProperty("Accept-Encoding","gzip");connection.setRequestProperty("Connection","keep-alive");connection.setRequestProperty("User-Agent",USER_AGENT);
        try (OutputStream output = connection.getOutputStream()) { output.write(client.toString().getBytes(StandardCharsets.UTF_8)); }
        int status=connection.getResponseCode();String responseType=connection.getContentType();
        if (status != 200 || responseType==null || !responseType.startsWith("application/octet-stream")) throw new NetworkFailure("receive",status,responseType);
        String refreshToken = connection.getHeaderField("Nyanko-Password-Refresh-Token");
        String password = connection.getHeaderField("Nyanko-Password");
        try (InputStream input = responseInput(connection)) { return new ReceivedSave(io.github.tuxkoh.bcsfe.core.IoStreams.readAll(input), refreshToken,password); }
        finally { connection.disconnect(); }
    }
    static UploadResult upload(io.github.tuxkoh.bcsfe.core.SaveDocument document,String password) throws Exception {
        io.github.tuxkoh.bcsfe.core.SaveDocument replacement=io.github.tuxkoh.bcsfe.core.SaveDocument.open(document.toBytes());
        Authentication authentication=authenticate(replacement,password);String auth=authentication.token;
        String iq=replacement.inquiryCode();
        JSONObject keyResponse=getJson(endpoints.save+"/v2/save/key?nonce="+randomHex(32),"Bearer "+auth); JSONObject key=keyResponse.optJSONObject("payload"); if(key==null||key.optString("key","").isEmpty())throw new IllegalStateException("Save key request failed");
        multipartUpload(key,replacement.toBytes());
        JSONObject metadataBody=new JSONObject().put("managedItemDetails",new JSONArray()).put("nonce",randomHex(32)).put("playTime",replacement.playTime()).put("rank",replacement.userRank()).put("receiptLogIds",new JSONArray()).put("signature_v1",signature(iq,"[]",true)).put("saveKey",key.getString("key"));
        String metadata=metadataBody.toString().replace("\\/","/");String[] signed=accountHeaders(iq,metadata); String[] headers=new String[signed.length+1];headers[0]="authorization: Bearer "+auth;System.arraycopy(signed,0,headers,1,signed.length);
        JSONObject result=postTransferJson(endpoints.save+"/v2/transfers",metadata,headers);
        JSONObject out=result.optJSONObject("payload"); if(out==null)throw new IllegalStateException("Transfer code request failed");String transfer=out.optString("transferCode"),pin=out.optString("pin");if(transfer.isEmpty()||pin.isEmpty())throw new IllegalStateException("Transfer code request failed");return new UploadResult(transfer,pin,authentication.password,replacement.toBytes());
    }
    static UploadResult uploadWithReplacementAccount(io.github.tuxkoh.bcsfe.core.SaveDocument document) throws Exception {
        byte[] source=document.toBytes();Exception lastFailure=null;
        for(int attempt=0;attempt<3;attempt++){
            try{
                io.github.tuxkoh.bcsfe.core.SaveDocument replacement=io.github.tuxkoh.bcsfe.core.SaveDocument.open(source);
                AccountResult account=createNewAccount(replacement);
                return upload(replacement,account.password);
            }catch(Exception failure){lastFailure=failure;}
        }
        throw lastFailure==null?new IllegalStateException("Upload failed"):lastFailure;
    }
    static ManagedUploadResult uploadManagedItems(io.github.tuxkoh.bcsfe.core.SaveDocument document,String password) throws Exception {
        io.github.tuxkoh.bcsfe.core.SaveDocument replacement=io.github.tuxkoh.bcsfe.core.SaveDocument.open(document.toBytes());
        Authentication authentication=authenticate(replacement,password);synchronizeManagedItems(replacement,authentication.token);return new ManagedUploadResult(authentication.password,replacement.toBytes());
    }
    private static Authentication authenticate(io.github.tuxkoh.bcsfe.core.SaveDocument document,String savedPassword) throws Exception {
        String iq=document.inquiryCode();
        if(savedPassword!=null&&!savedPassword.isEmpty()){
            try{return new Authentication(requestToken(document,iq,savedPassword),savedPassword);}catch(NetworkFailure ignored){}
        }
        JSONObject refreshBody=new JSONObject().put("accountCode",iq).put("passwordRefreshToken",document.passwordRefreshToken()).put("nonce",randomHex(32));
        JSONObject payload=null;JSONObject passwordResponse=null;
        try {
            passwordResponse=postAccountJson(endpoints.auth+"/v1/user/password",refreshBody,iq,null);
            payload=passwordResponse.optJSONObject("payload");
        } catch (NetworkFailure refreshFailed) {
            // A stale refresh token is recoverable through the account-created-at flow.
        }
        if(payload==null||payload.optString("password","").isEmpty()){
            JSONObject userBody=new JSONObject().put("accountCode",iq).put("accountCreatedAt",document.accountCreatedAt()).put("nonce",randomHex(32));
            passwordResponse=postAccountJson(endpoints.auth+"/v1/users",userBody,iq,null);
            payload=passwordResponse.optJSONObject("payload");
        }
        if(payload==null||payload.optString("password","").isEmpty())throw new IllegalStateException("Account authentication failed");
        String account=payload.optString("accountCode","");
        if(!account.isEmpty()&&!account.equals(iq)){
            document.setInquiryCode(account);
            iq=account;
        }
        if(!account.isEmpty()&&passwordResponse!=null&&passwordResponse.has("timestamp"))document.setAccountCreatedAt(passwordResponse.getLong("timestamp"));
        String refreshed=payload.optString("passwordRefreshToken","");if(!refreshed.isEmpty())document.setPasswordRefreshToken(refreshed);
        String password=payload.getString("password");
        return new Authentication(requestToken(document,iq,password),password);
    }
    private static String requestToken(io.github.tuxkoh.bcsfe.core.SaveDocument document,String iq,String password)throws Exception{JSONObject client=clientInfo(document.region().code());client.put("password",password).put("accountCode",iq);JSONObject token=postAccountJson(endpoints.auth+"/v1/tokens",client,iq,null);String auth=token.optJSONObject("payload")==null?"":token.getJSONObject("payload").optString("token","");if(auth.isEmpty())throw new IllegalStateException("Token request failed");return auth;}
    static AccountResult createNewAccount(io.github.tuxkoh.bcsfe.core.SaveDocument document) throws Exception {
        JSONObject created=getPublicJson(endpoints.backups+"/?action=createAccount&referenceId=");String inquiry=created.optString("accountId","");if(inquiry.isEmpty())throw new IllegalStateException("Account creation failed");
        document.setInquiryCode(inquiry);document.setPasswordRefreshToken("__________EXPECT_THIS_TO_FAIL___________");
        long accountCreatedAt;try{accountCreatedAt=document.accountCreatedAt();}catch(RuntimeException unavailable){accountCreatedAt=0;}
        JSONObject body=new JSONObject().put("accountCode",inquiry).put("accountCreatedAt",accountCreatedAt).put("nonce",randomHex(32));JSONObject user=postAccountJson(endpoints.auth+"/v1/users",body,inquiry,null);JSONObject payload=user.optJSONObject("payload");if(payload==null)throw new IllegalStateException("Password creation failed");
        String password=payload.optString("password","");String refresh=payload.optString("passwordRefreshToken","");String account=payload.optString("accountCode",inquiry);if(password.isEmpty()||refresh.isEmpty())throw new IllegalStateException("Password creation failed");document.setInquiryCode(account);document.setPasswordRefreshToken(refresh);if(user.has("timestamp"))try{document.setAccountCreatedAt(user.getLong("timestamp"));}catch(RuntimeException unavailable){}document.setShowBanMessage(false);
        JSONObject client=clientInfo(document.region().code());client.put("password",password).put("accountCode",account);JSONObject token=postAccountJson(endpoints.auth+"/v1/tokens",client,account,null);String auth=token.optJSONObject("payload")==null?"":token.getJSONObject("payload").optString("token","");if(auth.isEmpty())throw new IllegalStateException("Token request failed");try{synchronizeManagedItems(document,auth);}catch(Exception ignored){}
        JSONObject key=getJson(endpoints.save+"/v2/save/key?nonce="+randomHex(32),"Bearer "+auth).optJSONObject("payload");if(key==null||key.optString("key","").isEmpty())throw new IllegalStateException("Save key request failed");
        return new AccountResult(password);
    }
    private static void synchronizeManagedItems(io.github.tuxkoh.bcsfe.core.SaveDocument document,String auth) throws Exception {JSONObject body=new JSONObject().put("catfoodAmount",document.catFood()).put("isPaid",true).put("legendTicketAmount",document.legendTickets()).put("nonce",randomHex(32)).put("platinumTicketAmount",document.platinumTickets()).put("rareTicketAmount",document.rareTickets());String[] signed=accountHeaders(document.inquiryCode(),body.toString());String[] headers=new String[signed.length+1];System.arraycopy(signed,0,headers,0,signed.length);headers[headers.length-1]="Authorization: Bearer "+auth;JSONObject response=postJson(endpoints.managed+"/v1/managed-items",body,headers);if(response.optInt("statusCode",0)!=1)throw new IllegalStateException("Managed item upload failed");}
    private static JSONObject clientInfo(String region) throws Exception { return new JSONObject().put("clientInfo",new JSONObject().put("client",new JSONObject().put("countryCode",region.equals("jp")?"ja":region).put("version",150500)).put("device",new JSONObject().put("model","SM-G955F")).put("os",new JSONObject().put("type","android").put("version","9"))).put("nonce",randomHex(32)); }
    private static JSONObject postJson(String url,JSONObject body,String[] extra) throws Exception { HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setRequestMethod("POST");c.setDoOutput(true);c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Accept-Encoding","gzip");c.setRequestProperty("Connection","keep-alive");c.setRequestProperty("User-Agent",USER_AGENT);if(extra!=null)for(String h:extra){int p=h.indexOf(':');if(p>0)c.setRequestProperty(h.substring(0,p),h.substring(p+1).trim());}try(OutputStream o=c.getOutputStream()){o.write(body.toString().getBytes(StandardCharsets.UTF_8));}int status=c.getResponseCode();if(status/100!=2)throw new NetworkFailure(stage(url),status,c.getContentType());try(InputStream i=responseInput(c)){JSONObject response=new JSONObject(new String(io.github.tuxkoh.bcsfe.core.IoStreams.readAll(i),StandardCharsets.UTF_8));if(response.optInt("statusCode",0)!=1)throw new NetworkFailure(stage(url),status,"statusCode="+response.optInt("statusCode",0));return response;}finally{c.disconnect();}}
    private static JSONObject postTransferJson(String url,String body,String[] extra) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();
        connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setConnectTimeout(15000);connection.setReadTimeout(30000);
        connection.setRequestProperty("Content-Type","application/json");
        if(extra!=null)for(String header:extra){int split=header.indexOf(':');if(split>0)connection.setRequestProperty(header.substring(0,split),header.substring(split+1).trim());}
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(bytes.length);
        try(OutputStream output=connection.getOutputStream()){output.write(bytes);}
        int status=connection.getResponseCode();String responseType=connection.getContentType();
        if(status/100!=2)throw new NetworkFailure(stage(url),status,responseType);
        try(InputStream input=responseInput(connection)){
            JSONObject json=new JSONObject(new String(io.github.tuxkoh.bcsfe.core.IoStreams.readAll(input),StandardCharsets.UTF_8));
            if(json.optInt("statusCode",0)!=1)throw new NetworkFailure(stage(url),status,"statusCode="+json.optInt("statusCode",0));
            return json;
        }finally{connection.disconnect();}
    }
    private static JSONObject postAccountJson(String url,JSONObject body,String iq,String auth)throws Exception{String[] signed=accountHeaders(iq,body.toString());String[] headers=new String[signed.length+(auth==null?0:1)];System.arraycopy(signed,0,headers,0,signed.length);if(auth!=null)headers[headers.length-1]="Authorization: Bearer "+auth;return postJson(url,body,headers);}
    private static JSONObject getJson(String url,String auth) throws Exception {HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setRequestProperty("Authorization",auth);c.setRequestProperty("Accept-Encoding","gzip");c.setRequestProperty("Connection","keep-alive");c.setRequestProperty("nyanko-timestamp",String.valueOf(System.currentTimeMillis()/1000));c.setRequestProperty("User-Agent",USER_AGENT);int status=c.getResponseCode();if(status/100!=2)throw new NetworkFailure(stage(url),status,c.getContentType());try(InputStream i=responseInput(c)){JSONObject response=new JSONObject(new String(io.github.tuxkoh.bcsfe.core.IoStreams.readAll(i),StandardCharsets.UTF_8));if(response.optInt("statusCode",0)!=1)throw new NetworkFailure(stage(url),status,"statusCode="+response.optInt("statusCode",0));return response;}finally{c.disconnect();}}
    private static JSONObject getPublicJson(String url) throws Exception {HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setRequestProperty("User-Agent",USER_AGENT);int status=c.getResponseCode();if(status/100!=2)throw new NetworkFailure(stage(url),status,c.getContentType());try(InputStream i=responseInput(c)){return new JSONObject(new String(io.github.tuxkoh.bcsfe.core.IoStreams.readAll(i),StandardCharsets.UTF_8));}finally{c.disconnect();}}
    private static void multipartUpload(JSONObject key,byte[] save) throws Exception {
        String url=key.optString("url","https://nyanko-service-data-prd.s3.amazonaws.com/");
        MultipartBody.Builder form=new MultipartBody.Builder().setType(MultipartBody.FORM);
        java.util.Iterator<String> names=key.keys();
        while(names.hasNext()){String name=names.next();if(!name.equals("url"))form.addFormDataPart(name,null,RequestBody.create(key.getString(name),MediaType.get("text/plain")));}
        form.addFormDataPart("file","file.sav",RequestBody.create(save,MediaType.get("application/octet-stream")));
        OkHttpClient client=new OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(60,TimeUnit.SECONDS).writeTimeout(60,TimeUnit.SECONDS).build();
        Request request=new Request.Builder().url(url).header("Accept-Encoding","gzip").header("Connection","keep-alive").header("User-Agent",USER_AGENT).post(form.build()).build();
        try(Response response=client.newCall(request).execute()){if(response.code()!=204)throw new NetworkFailure("save-upload",response.code(),response.header("Content-Type"));}
    }
    private static String stage(String url) {if(url.contains("/user/password"))return "refresh-password";if(url.contains("/v1/users"))return "create-password";if(url.contains("/v1/tokens"))return "auth-token";if(url.contains("/managed-items"))return "managed-items";if(url.contains("/save/key"))return "save-key";if(url.contains("/transfers"))return "transfer-codes";if(url.contains("createAccount"))return "create-account";return "request";}
    private static InputStream responseInput(HttpURLConnection connection) throws Exception {InputStream input=connection.getInputStream();return "gzip".equalsIgnoreCase(connection.getContentEncoding())?new java.util.zip.GZIPInputStream(input):input;}
    private static String[] accountHeaders(String iq,String data)throws Exception{return new String[]{"nyanko-signature: "+signature(iq,data,false),"nyanko-timestamp: "+(System.currentTimeMillis()/1000),"nyanko-signature-version: 1","nyanko-signature-algorithm: HMACSHA256"};}
    private static String signature(String iq,String data,boolean v1)throws Exception{return signatureWithRandom(iq,data,v1,randomHex(v1?40:64));}
    static String signatureWithRandom(String iq,String data,boolean v1,String random)throws Exception{int expected=v1?40:64;if(random.length()!=expected)throw new IllegalArgumentException("Invalid signature nonce");return random+hex(hmac(v1?"HmacSHA1":"HmacSHA256",iq+random,v1?data+data:data));}
    private static byte[] hmac(String alg,String key,String data)throws Exception{Mac m=Mac.getInstance(alg);m.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),alg));return m.doFinal(data.getBytes(StandardCharsets.UTF_8));}
    private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(java.util.Locale.ROOT,"%02x",x));return s.toString();}
    private static String randomHex(int count) { StringBuilder b = new StringBuilder(count); for (int i=0;i<count;i++) b.append("0123456789abcdef".charAt(SECURE_RANDOM.nextInt(16))); return b.toString(); }
}
