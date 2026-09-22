package dev.composemc.forge.sync;

import dev.composemc.sync.SyncCodec;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import java.io.IOException;
import java.util.Objects;

/** Bridges native FriendlyByteBuf codecs into portable schema framing. Construct/use on the owning game thread. */
public final class MinecraftSyncCodecs {
    public static <T> SyncCodec<T> buffer(String id,int maximumBytes,PacketCodec<T> codec) {
        if(maximumBytes<1)throw new IllegalArgumentException("Invalid native codec limit");
        Objects.requireNonNull(codec);
        Thread owner=Thread.currentThread();
        return SyncCodec.of("buffer:"+id+":"+maximumBytes,(out,value)->{
            if(Thread.currentThread()!=owner)throw new IllegalStateException("Registry codec used from another thread");
            var bytes=Unpooled.buffer(Math.min(256,maximumBytes),maximumBytes);
            try {
                var buffer=new FriendlyByteBuf(bytes);
                codec.encode(buffer,value);
                out.writeInt(bytes.readableBytes());
                byte[] scratch=new byte[Math.min(4096,Math.max(1,bytes.readableBytes()))];
                while(bytes.isReadable()){int count=Math.min(scratch.length,bytes.readableBytes());bytes.readBytes(scratch,0,count);out.write(scratch,0,count);}
            }catch(IndexOutOfBoundsException | io.netty.handler.codec.EncoderException invalid){throw new IOException("Native value cannot be encoded within its codec limit: maximumBytes="+maximumBytes+"",invalid);}
            finally{bytes.release();}
        },in->{
            if(Thread.currentThread()!=owner)throw new IllegalStateException("Registry codec used from another thread");
            int size=in.readInt();if(size<0||size>maximumBytes)throw new IOException("Invalid native value size");
            var bytes=Unpooled.buffer(Math.min(4096,size),Math.max(1,size));
            try {
                // Grow only after bytes actually arrive; a truncated length prefix must not allocate the declared value.
                byte[] scratch=new byte[Math.min(4096,size)];
                for(int remaining=size;remaining>0;){
                    int count=Math.min(scratch.length,remaining);in.readFully(scratch,0,count);bytes.writeBytes(scratch,0,count);remaining-=count;
                }
                T decoded=codec.decode(new FriendlyByteBuf(bytes));
                if(bytes.isReadable())throw new IOException("Trailing native value data");return decoded;
            }catch(IndexOutOfBoundsException invalid){throw new IOException("Truncated native value",invalid);}
            finally{bytes.release();}
        });
    }
    private MinecraftSyncCodecs(){}
}
