package com.evolution.analysis.filesystem;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;
import javax.net.ssl.*;

/** Small streaming HTTP/1.1 broker. DNS addresses are checked before the TCP connect; TLS still verifies the hostname.
 * Redirects are rejected. Headers and metadata are bounded, response payloads are streamed. */
final class PinnedHttps {
    private final GitHostPolicy policy;
    PinnedHttps(GitHostPolicy policy){this.policy=policy;}
    InputStream request(URI uri,String method,Map<String,String> headers,byte[] body,BooleanSupplier cancelled)throws IOException{
        if(!"https".equals(uri.getScheme())||uri.getRawUserInfo()!=null||uri.getFragment()!=null)throw new IOException("ENDPOINT_DENIED");
        var endpoint=policy.endpoint(uri);Socket tcp=new Socket();
        try{
            int port=uri.getPort()==-1?443:uri.getPort();IOException unavailable=null;boolean connected=false;
            for(var address:endpoint.addresses())try{tcp.connect(new InetSocketAddress(address,port),30_000);connected=true;break;}catch(IOException failed){unavailable=failed;tcp.close();tcp=new Socket();}
            if(!connected)throw new IOException("LFS_CONNECTION_UNAVAILABLE",unavailable);tcp.setSoTimeout(60_000);
            var socket=(SSLSocket)policy.tls().getSocketFactory().createSocket(tcp,uri.getHost(),port,true);
            var parameters=socket.getSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");if(!uri.getHost().startsWith("[")&&!uri.getHost().matches("[0-9.]+"))parameters.setServerNames(List.of(new SNIHostName(uri.getHost())));socket.setSSLParameters(parameters);socket.startHandshake();
            String path=uri.getRawPath();if(path==null||path.isEmpty())path="/";if(uri.getRawQuery()!=null)path+="?"+uri.getRawQuery();
            if(path.indexOf('\r')>=0||path.indexOf('\n')>=0)throw new IOException("ENDPOINT_DENIED");
            var text=new StringBuilder(method+" "+path+" HTTP/1.1\r\nHost: "+uri.getHost()+(port==443?"":":"+port)+"\r\nConnection: close\r\nAccept-Encoding: identity\r\nContent-Length: "+body.length+"\r\n");
            var auth=policy.authorization(uri);if(auth.isPresent())text.append("Authorization: ").append(auth.orElseThrow()).append("\r\n");
            for(var h:headers.entrySet()){
                if(!h.getKey().matches("[A-Za-z-]+")||!Set.of("accept","content-type","authorization").contains(h.getKey().toLowerCase(Locale.ROOT))||h.getValue().contains("\r")||h.getValue().contains("\n"))throw new IOException("HTTP_HEADER_DENIED");
                if(h.getKey().equalsIgnoreCase("authorization")&&auth.isPresent())throw new IOException("AUTHORIZATION_CONFLICT");text.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
            }
            text.append("\r\n");var output=socket.getOutputStream();output.write(text.toString().getBytes(StandardCharsets.US_ASCII));output.write(body);output.flush();
            var input=new BufferedInputStream(socket.getInputStream(),65536);String status=line(input,8192);if(!status.matches("HTTP/1\\.[01] 2[0-9][0-9](?: .*)?"))throw new IOException(status.contains(" 401 ")||status.contains(" 403 ")?"LFS_ACCESS_REQUIRED":"LFS_HTTP_UNAVAILABLE");
            var response=new TreeMap<String,String>(String.CASE_INSENSITIVE_ORDER);int length=0;String line;
            while(!(line=line(input,8192)).isEmpty()){
                length+=line.length();if(length>65536)throw new IOException("HTTP_HEADERS_OVERSIZE");int colon=line.indexOf(':');if(colon<1||response.put(line.substring(0,colon),line.substring(colon+1).strip())!=null)throw new IOException("HTTP_HEADERS_INVALID");
            }
            InputStream framed=input;
            if(response.containsKey("Content-Encoding")&&!response.get("Content-Encoding").equalsIgnoreCase("identity"))throw new IOException("HTTP_ENCODING_DENIED");
            if(response.containsKey("Transfer-Encoding")){
                if(!response.get("Transfer-Encoding").equalsIgnoreCase("chunked")||response.containsKey("Content-Length"))throw new IOException("HTTP_FRAMING_INVALID");framed=new Chunked(input);
            }else if(response.containsKey("Content-Length")){
                long size;try{size=Long.parseLong(response.get("Content-Length"));if(size<0)throw new NumberFormatException();}catch(NumberFormatException invalid){throw new IOException("HTTP_LENGTH_INVALID");}framed=new GitSnapshotAcquirer.LimitedInput(input,size);
            }
            final InputStream payload=framed;
            return new FilterInputStream(payload){
                private void check()throws IOException{if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("CANCELLED_CHECKPOINT");}
                @Override public int read()throws IOException{check();return super.read();}
                @Override public int read(byte[] b,int off,int len)throws IOException{check();return super.read(b,off,len);}
                @Override public void close()throws IOException{socket.close();}
            };
        }catch(IOException|RuntimeException failure){tcp.close();throw failure;}
    }
    static String line(InputStream in,int max)throws IOException{
        var bytes=new ByteArrayOutputStream();int b;while((b=in.read())!=-1){if(b==10){byte[] value=bytes.toByteArray();if(value.length==0||value[value.length-1]!=13)throw new IOException("HTTP_LINE_INVALID");return new String(value,0,value.length-1,StandardCharsets.US_ASCII);}if(bytes.size()>=max)throw new IOException("HTTP_LINE_OVERSIZE");bytes.write(b);}throw new EOFException();
    }
    static final class Chunked extends InputStream{
        final InputStream input;long remaining;boolean ended,needCrlf;
        Chunked(InputStream input){this.input=input;}
        private boolean ready()throws IOException{
            if(ended)return false;if(remaining>0)return true;if(needCrlf&&(!line(input,2).isEmpty()))throw new IOException("HTTP_CHUNK_INVALID");
            String size=line(input,1024).split(";",2)[0];try{remaining=Long.parseLong(size,16);if(remaining<0)throw new NumberFormatException();}catch(NumberFormatException invalid){throw new IOException("HTTP_CHUNK_INVALID");}
            if(remaining==0){int trailerBytes=0;String trailer;while(!(trailer=line(input,8192)).isEmpty()){trailerBytes+=trailer.length();if(trailerBytes>65536)throw new IOException("HTTP_TRAILERS_OVERSIZE");}ended=true;return false;}needCrlf=true;return true;
        }
        @Override public int read()throws IOException{if(!ready())return -1;int b=input.read();if(b<0)throw new EOFException();remaining--;return b;}
        @Override public int read(byte[] b,int off,int len)throws IOException{if(len==0)return 0;if(!ready())return -1;int n=input.read(b,off,(int)Math.min(len,remaining));if(n<0)throw new EOFException();remaining-=n;return n;}
    }
}
