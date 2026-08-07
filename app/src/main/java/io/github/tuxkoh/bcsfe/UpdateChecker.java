package io.github.tuxkoh.bcsfe;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class UpdateChecker {
    private static final String[] LATEST_RELEASE_URLS={
            "https://api.github.com/repos/tuxKOH/BCSFE-Android/releases/latest",
            "https://gh-proxy.com/https://api.github.com/repos/tuxKOH/BCSFE-Android/releases/latest"
    };
    private UpdateChecker() {}

    static final class Result {
        final String version;
        final String pageUrl;
        Result(String version,String pageUrl){this.version=version;this.pageUrl=pageUrl;}
    }

    static Result latest(String currentVersion)throws Exception{
        for(String endpoint:LATEST_RELEASE_URLS){try{Result result=requestLatest(endpoint,currentVersion);if(result!=null)return result;}catch(Exception ignored){}}
        return null;
    }

    private static Result requestLatest(String endpoint,String currentVersion)throws Exception{
        HttpURLConnection connection=(HttpURLConnection)new URL(endpoint).openConnection();
        connection.setConnectTimeout(5000);connection.setReadTimeout(5000);
        connection.setRequestProperty("Accept","application/vnd.github+json");
        connection.setRequestProperty("User-Agent","BCSFE-Android/"+currentVersion);
        connection.setRequestProperty("X-GitHub-Api-Version","2022-11-28");
        try{
            if(connection.getResponseCode()!=200)throw new java.io.IOException("Release endpoint unavailable");
            try(InputStream input=connection.getInputStream()){
                JSONObject release=new JSONObject(new String(io.github.tuxkoh.bcsfe.core.IoStreams.readAll(input),StandardCharsets.UTF_8));
                String version=release.optString("tag_name","").trim(),pageUrl=release.optString("html_url","").trim();
                if(!isNewer(version,currentVersion)||!pageUrl.startsWith("https://github.com/"))return null;
                return new Result(version,pageUrl);
            }
        }finally{connection.disconnect();}
    }

    static boolean isNewer(String candidate,String current){
        int[] candidateParts=parseVersion(candidate),currentParts=parseVersion(current);
        if(candidateParts==null||currentParts==null)return false;
        for(int i=0;i<Math.max(candidateParts.length,currentParts.length);i++){
            int a=i<candidateParts.length?candidateParts[i]:0,b=i<currentParts.length?currentParts[i]:0;
            if(a!=b)return a>b;
        }
        return false;
    }

    private static int[] parseVersion(String value){
        if(value==null)return null;String clean=value.trim();
        if(clean.startsWith("v")||clean.startsWith("V"))clean=clean.substring(1);
        int suffix=clean.indexOf('-');if(suffix>=0)clean=clean.substring(0,suffix);
        if(clean.isEmpty())return null;String[] pieces=clean.split("\\.");int[] result=new int[pieces.length];
        try{for(int i=0;i<pieces.length;i++){if(pieces[i].isEmpty())return null;result[i]=Integer.parseInt(pieces[i]);if(result[i]<0)return null;}return result;}catch(NumberFormatException ignored){return null;}
    }
}
