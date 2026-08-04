package ir.mehranlatifi83.roozara.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import ir.mehranlatifi83.roozara.R;
import ir.mehranlatifi83.roozara.ui.MainActivity;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Blocks internet access during the sleep window by routing every route into a local
 * tunnel whose packets are read and discarded.
 */
public class SleepVpnService extends VpnService {

    private static final String TAG        = "SleepVpnService";
    private static final String CHANNEL_ID = "sleep_vpn_channel";
    private static final int    NOTIF_ID   = 1;

    // One active VPN interface per app process; other bedtime components may close it.
    private static ParcelFileDescriptor vpnInterface;

    private volatile boolean draining;
    private Thread drainThread;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());
        // START_STICKY restarts this service with a null intent after the system kills
        // it. The old descriptor is dead by then, so always rebuild rather than trusting
        // the cached one.
        if (!establishVpnTunnel()) {
            // A Toast is unreliable here: this runs from a background-started service,
            // and a user who is asleep or blind would not see it anyway. A notification
            // persists until it is read.
            notifyBlockingFailed();
            stopSelf();
            return START_NOT_STICKY;
        }
        startDraining();
        return START_STICKY;
    }

    /**
     * Starts the tunnel if it is not already up. Called whenever the lock screen comes
     * back to the foreground, so a tunnel lost to a process kill or to another VPN app
     * is rebuilt instead of silently leaving the user online for the rest of the night.
     */
    public static void ensureRunning(android.content.Context ctx) {
        if (VpnService.prepare(ctx) != null) return;  // No consent; nothing we can do.
        if (vpnInterface != null) return;
        try {
            ctx.startForegroundService(new Intent(ctx, SleepVpnService.class));
        } catch (Exception e) {
            Log.w(TAG, "Could not restart the blocking tunnel", e);
        }
    }

    private void notifyBlockingFailed() {
        getSystemService(NotificationManager.class).notify(7,
                new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle(getString(R.string.vpn_permission_missing_title))
                        .setContentText(getString(R.string.vpn_start_failed))
                        .setSmallIcon(R.drawable.ic_moon)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .build());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopDraining();
        disconnect();
    }

    /**
     * Called when the user, or another VPN app, takes the VPN slot away from us. Without
     * handling this the tunnel is gone but the service keeps running, so the user
     * silently has full internet for the rest of the night.
     */
    @Override
    public void onRevoke() {
        Log.w(TAG, "VPN permission revoked by the system or another app");
        stopDraining();
        disconnect();
        stopSelf();
        super.onRevoke();
    }

    /** Closes the VPN tunnel. Safe to call from any thread or context. */
    public static synchronized void disconnect() {
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing VPN interface", e);
            } finally {
                vpnInterface = null;
            }
        }
    }

    private synchronized boolean establishVpnTunnel() {
        disconnect();

        Builder builder = new Builder().setSession(getString(R.string.app_name));

        // IPv4 and IPv6 are configured independently. Adding an IPv6 address throws on
        // devices and networks without IPv6 support, and when that exception escaped it
        // took the whole tunnel down with it — leaving the user fully online all night
        // while the app reported success. Each family is now best-effort, and the tunnel
        // is established as long as at least one of them applied.
        boolean anyFamily = false;

        try {
            builder.addAddress("10.0.0.2", 32).addRoute("0.0.0.0", 0);
            anyFamily = true;
        } catch (Exception e) {
            Log.w(TAG, "IPv4 route unavailable", e);
        }

        try {
            // Modern mobile networks commonly prefer IPv6. Without this route, IPv6
            // traffic can bypass an IPv4-only blocking tunnel.
            builder.addAddress("fd00::2", 128).addRoute("::", 0);
            anyFamily = true;
        } catch (Exception e) {
            Log.w(TAG, "IPv6 route unavailable", e);
        }

        if (!anyFamily) {
            Log.e(TAG, "Neither IPv4 nor IPv6 could be routed; cannot block traffic");
            return false;
        }

        try {
            // Our own process is excluded so the app stays responsive and its own
            // scheduling work is never affected by the block it installed.
            builder.addDisallowedApplication(getPackageName());
        } catch (Exception e) {
            Log.w(TAG, "Could not exclude own package from the tunnel", e);
        }

        try {
            vpnInterface = builder.establish();
        } catch (Exception e) {
            Log.e(TAG, "Failed to establish VPN tunnel", e);
            return false;
        }

        if (vpnInterface == null) {
            // establish() returns null when consent was never granted or was withdrawn.
            Log.e(TAG, "establish() returned null — VPN consent is missing");
            return false;
        }
        return true;
    }

    /**
     * Reads and discards everything the system writes into the tunnel.
     *
     * Without this the descriptor is never drained, so blocking depends on the kernel
     * queue filling up. On some devices that queue drains or is large enough that
     * traffic keeps flowing for a while, which is why the block could appear partial.
     * Actively discarding packets makes the block immediate and deterministic.
     */
    private void startDraining() {
        if (draining) return;
        final ParcelFileDescriptor fd = vpnInterface;
        if (fd == null) return;

        draining = true;
        drainThread = new Thread(() -> {
            ByteBuffer packet = ByteBuffer.allocate(32767);
            try (FileInputStream in = new FileInputStream(fd.getFileDescriptor())) {
                while (draining) {
                    int read = in.read(packet.array());
                    if (read < 0) break;   // Tunnel closed.
                    packet.clear();        // Discard: this is the block.
                }
            } catch (IOException e) {
                // Expected when the tunnel is torn down at wake time.
                if (draining) Log.w(TAG, "Tunnel read ended", e);
            }
        }, "SleepVpnDrain");
        drainThread.setDaemon(true);
        drainThread.start();
    }

    private void stopDraining() {
        draining = false;
        if (drainThread != null) {
            drainThread.interrupt();
            drainThread = null;
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        PendingIntent openApp = PendingIntent.getActivity(
                this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setSmallIcon(R.drawable.ic_moon)
                .setContentIntent(openApp)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }
}
