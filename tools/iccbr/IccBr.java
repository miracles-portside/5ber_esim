import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * IccBr - run as shell via app_process64.
 * Opens a logical channel on a UICC with a LIVE local Binder (so the framework's
 * "client died" auto-close does not kill the session), then acts as an APDU terminal.
 *
 * usage: IccBr <subId> <slotIndex> <portIndex> [openAid] [callingPackage] [mode]
 *   mode: logical (default) | basic (no channel; uses basic channel transmit)
 * stdin commands (one per line):
 *    <cla> <ins> <p1> <p2> <p3> <datahex|->     e.g.  81 E2 91 00 03 BF2D00
 *    sleep <seconds>
 *    close
 *    quit
 */
public class IccBr {
    static Object tp;
    static Method mOpen;
    static Method mTx;
    static Method mTxBasic;
    static Method mClose;
    static Object binderKeepAlive;
    static int gSubId, gChannel = -1;
    static String gPkg;
    static boolean gBasic = false;

    public static void main(String[] args) throws Exception {
        int subId = args.length > 0 ? Integer.parseInt(args[0]) : 22;
        int slotIndex = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        int portIndex = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        String aid = args.length > 3 ? args[3] : "a0000005591010ffffffff8900000200";
        String pkg = args.length > 4 ? args[4] : "com.android.shell";
        String mode = args.length > 5 ? args[5] : "logical";
        gBasic = mode.equals("basic");
        gSubId = subId;
        gPkg = pkg;

        Object svc = android.os.ServiceManager.getService("phone");
        Class<?> stub = Class.forName("com.android.internal.telephony.ITelephony$Stub");
        tp = stub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, svc);
        Class<?> reqClass = Class.forName("com.android.internal.telephony.IccLogicalChannelRequest");

        for (Method m : tp.getClass().getMethods()) {
            if (m.getName().equals("iccOpenLogicalChannel") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].equals(reqClass)) mOpen = m;
            if (m.getName().equals("iccTransmitApduLogicalChannel") && m.getParameterCount() == 8) mTx = m;
            if (m.getName().equals("iccTransmitApduBasicChannel") && m.getParameterCount() == 8) mTxBasic = m;
            if (m.getName().equals("iccCloseLogicalChannel") && m.getParameterCount() == 2) mClose = m;
        }
        if (mTx == null || mTxBasic == null) { System.out.println("[!] methods not found"); return; }
        System.out.println("[i] mode=" + mode + " mTx=" + (mTx != null) + " mTxBasic=" + (mTxBasic != null));

        if (!gBasic) {
            Object req = reqClass.getDeclaredConstructor().newInstance();
            setField(req, "subId", subId);
            setField(req, "slotIndex", slotIndex);
            setField(req, "portIndex", portIndex);
            setField(req, "callingPackage", pkg);
            setField(req, "aid", aid);
            setField(req, "p2", 0);
            setField(req, "channel", -1);
            binderKeepAlive = new android.os.Binder();
            setField(req, "binder", binderKeepAlive);

            Object resp;
            try {
                resp = mOpen.invoke(tp, req);
            } catch (Throwable t) {
                System.out.println("[!] open threw: " + t);
                if (t.getCause() != null) System.out.println("[!] cause: " + t.getCause());
                return;
            }
            System.out.println("[i] openResp: " + dump(resp));
            gChannel = (int) getLong(resp, "Channel");
            System.out.println("[=] CHANNEL=" + gChannel);
            if (gChannel < 0) { System.out.println("[!] no channel"); return; }
        } else {
            System.out.println("[=] BASIC CHANNEL mode");
        }
        System.out.flush();

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.equals("quit")) break;
            if (line.equals("close")) { closeCh(); break; }
            if (line.startsWith("sleep ")) {
                long s = Long.parseLong(line.substring(6).trim());
                Thread.sleep(s * 1000);
                System.out.println("[i] slept " + s + "s, channel still=" + gChannel);
                System.out.flush();
                continue;
            }
            String[] t = line.split("\\s+");
            try {
                int cla = Integer.parseInt(t[0], 16);
                int ins = Integer.parseInt(t[1], 16);
                int p1 = Integer.parseInt(t[2], 16);
                int p2 = Integer.parseInt(t[3], 16);
                int p3 = Integer.parseInt(t[4], 16);
                String data = (t.length > 5 && !t[5].equals("-")) ? t[5] : "";
                Object r;
                if (gBasic) {
                    r = mTxBasic.invoke(tp, subId, gPkg, cla, ins, p1, p2, p3, data);
                } else {
                    r = mTx.invoke(tp, subId, gChannel, cla, ins, p1, p2, p3, data);
                }
                System.out.println(line + " => " + r);
                if (r instanceof String) {
                    String s = (String) r;
                    if (s != null && s.length() >= 4 && s.regionMatches(true, s.length() - 4, "61", 0, 2)) {
                        int le = Integer.parseInt(s.substring(s.length() - 2), 16);
                        Object g;
                        if (gBasic) g = mTxBasic.invoke(tp, subId, gPkg, cla, 0xC0, 0, 0, le, "");
                        else g = mTx.invoke(tp, subId, gChannel, cla, 0xC0, 0, 0, le, "");
                        System.out.println("  GR => " + g);
                    }
                }
            } catch (Throwable e) {
                System.out.println(line + " => ERR " + e + (e.getCause() != null ? " / " + e.getCause() : ""));
            }
            System.out.flush();
        }
        closeCh();
        System.out.println("[i] done");
    }

    static void closeCh() {
        if (!gBasic && gChannel >= 0 && mClose != null) {
            try { mClose.invoke(tp, gSubId, gChannel); System.out.println("[i] channel closed"); }
            catch (Throwable t) { System.out.println("[!] close threw: " + t); }
        }
    }

    static void setField(Object o, String name, Object v) {
        try { o.getClass().getField(name).set(o, v); }
        catch (Throwable t) { System.out.println("[!] setField " + name + " failed: " + t); }
    }

    static long getLong(Object o, String prop) {
        String getter = "get" + prop;
        for (Method m : o.getClass().getMethods()) {
            if (m.getName().equals(getter) && m.getParameterCount() == 0) {
                try { m.setAccessible(true); return ((Number) m.invoke(o)).longValue(); } catch (Throwable t) {}
            }
        }
        try { Field f = o.getClass().getDeclaredField("m" + prop); f.setAccessible(true); return ((Number) f.get(o)).longValue(); } catch (Throwable t) {}
        try { Field f = o.getClass().getDeclaredField(lower(prop)); f.setAccessible(true); return ((Number) f.get(o)).longValue(); } catch (Throwable t) {}
        return -999;
    }

    static String dump(Object o) {
        if (o == null) return "null";
        StringBuilder sb = new StringBuilder(o.getClass().getName()).append("{");
        for (Field f : o.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            try {
                f.setAccessible(true);
                Object v = f.get(o);
                if (v instanceof byte[]) v = hex((byte[]) v);
                sb.append(f.getName()).append("=").append(v).append(" ");
            } catch (Throwable t) {}
        }
        return sb.append("}").toString();
    }

    static String hex(byte[] b) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static String lower(String s) {
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
