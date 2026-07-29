package org.jumpserver.chen.framework.ws.io;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;


@Slf4j
public class PacketIO {

    private static final DateTimeFormatter LOCAL_DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Getter
    private final WebSocketSession wsSession;

    public PacketIO(WebSocketSession ws) {
        this.wsSession = ws;
    }

    private static final Gson GSON = new GsonBuilder()
            .setDateFormat("yyyy-MM-dd HH:mm:ss")
            .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeTypeAdapter().nullSafe())
            .create();

    private static final class LocalDateTimeTypeAdapter extends TypeAdapter<LocalDateTime> {

        @Override
        public void write(JsonWriter out, LocalDateTime value) throws IOException {
            out.value(value.format(LOCAL_DATE_TIME_FORMATTER));
        }

        @Override
        public LocalDateTime read(JsonReader in) throws IOException {
            return LocalDateTime.parse(in.nextString(), LOCAL_DATE_TIME_FORMATTER);
        }
    }

    public void sendPacket(Packet packet) {
        synchronized (this.wsSession) {
            try {

                String json = GSON.toJson(packet);
                this.wsSession.sendMessage(new TextMessage(json));
            } catch (IOException e) {
                log.error(e.getMessage());

            }
        }
    }

    public void sendPacket(String type, Object data) {
        Packet packet = new Packet();
        packet.setType(type);
        packet.setData(data);
        this.sendPacket(packet);
    }

    public void close() {
        try {
            this.wsSession.close();
        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }

}
