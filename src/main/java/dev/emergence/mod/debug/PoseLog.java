package dev.emergence.mod.debug;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Motion diagnostics (system property {@code emergence.poseLog=true}): the server writes every moving body's synced
 * pose each tick to {@code pose-server.csv}, the client every frame what it received and what it drew to
 * {@code pose-client.csv} (in the game directory), for checking that moving parts are drawn smoothly.
 */
public final class PoseLog {
    private PoseLog() {}

    public static final boolean ENABLED = Boolean.getBoolean("emergence.poseLog");
    private static PrintWriter server, client;

    public static synchronized void server(String line) {
        if (server == null) server = open("pose-server.csv", "gameTime,key,slider,angle,omega,timeScale,commonScale,costMs,near");
        server.println(line);
    }

    public static synchronized void client(String line) {
        if (client == null) client = open("pose-client.csv", "nanos,now,key,poseTime,angle,omega,drawn");
        client.println(line);
    }

    private static PrintWriter open(String name, String header) {
        try {
            PrintWriter w = new PrintWriter(new FileWriter(name), true);
            w.println(header);
            return w;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
