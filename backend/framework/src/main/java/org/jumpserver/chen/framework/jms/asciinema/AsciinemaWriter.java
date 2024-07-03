package org.jumpserver.chen.framework.jms.asciinema;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import org.jumpserver.chen.framework.utils.TimeUtils;

import java.io.IOException;
import java.io.Writer;
import java.time.Instant;

public class AsciinemaWriter {

    private static final int VERSION = 2;
    private static final String DEFAULT_SHELL = "/bin/bash";
    private static final String DEFAULT_TERM = "xterm";
    private static final String NEW_LINE = "\n";

    private final Config config;
    private final Writer writer;
    private final long timestampNano;

    public AsciinemaWriter(Writer writer) {
        this.config = new Config();
        this.config.width = 80;
        this.config.height = 40;
        this.config.envShell = DEFAULT_SHELL;
        this.config.envTerm = DEFAULT_TERM;

        this.writer = writer;
        this.timestampNano = TimeUtils.getNowUnixNanoTIme();
    }

    public void writeHeader() throws IOException {
        Header header = new Header();
        header.version = VERSION;
        header.width = this.config.width;
        header.height = this.config.height;
        header.timestamp = this.config.timestamp.getEpochSecond();
        header.title = this.config.title;
        header.env = new Env();
        header.env.shell = this.config.envShell;
        header.env.term = this.config.envTerm;

        Gson gson = new Gson();
        String json = gson.toJson(header) + NEW_LINE;
        this.writer.write(json);
    }

    public void writeRow(byte[] p) throws IOException {
        long now = TimeUtils.getNowUnixNanoTIme();
        double ts = (now - this.timestampNano) / 1_000_000_000.0;
        this.writeStdout(ts, p);
    }

    public void writeStdout(double ts, byte[] data) throws IOException {
        Object[] row = new Object[]{ts, "o", new String(data)};
        Gson gson = new Gson();
        String json = gson.toJson(row) + NEW_LINE;
        this.writer.write(json);
    }

    private static class Config {
        int width;
        int height;
        String envShell;
        String envTerm;
        Instant timestamp = Instant.now();
        String title;
    }

    private static class Header {
        int version;
        int width;
        int height;
        long timestamp;
        String title;
        Env env;
    }

    private static class Env {
        @SerializedName("SHELL")
        String shell;
        @SerializedName("TERM")
        String term;
    }
}