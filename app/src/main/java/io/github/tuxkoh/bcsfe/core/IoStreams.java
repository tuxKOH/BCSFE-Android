package io.github.tuxkoh.bcsfe.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class IoStreams {
    private IoStreams() {}

    public static byte[] readAll(InputStream input)throws IOException{
        ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
        while((count=input.read(buffer))!=-1)output.write(buffer,0,count);
        return output.toByteArray();
    }
}
