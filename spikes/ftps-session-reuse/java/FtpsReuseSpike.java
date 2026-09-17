import javax.net.ssl.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Phase S spike: can a JSSE/Conscrypt client resume the FTPS control-channel
 * TLS session on the data channel, as servers with require_ssl_reuse demand?
 *
 * Technique under test: wrap the already-connected data socket with
 *   factory.createSocket(rawDataSocket, controlHost, controlPort, true)
 * The host/port arguments are only a hint for the client session cache, so
 * passing the CONTROL port makes the lookup hit the control session and the
 * client offers it for resumption. No reflection, no JDK internals.
 *
 * Usage: FtpsReuseSpike <host> <port> <user> <pass> <remoteFile> [--no-reuse] [--rest N]
 */
public class FtpsReuseSpike {

    private Socket rawControl;
    private SSLSocket control;
    private BufferedReader in;
    private Writer out;
    private SSLSocketFactory factory;
    private final String host;
    private final int port;
    private final boolean reuseSession;

    FtpsReuseSpike(String host, int port, boolean reuseSession) {
        this.host = host;
        this.port = port;
        this.reuseSession = reuseSession;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("usage: FtpsReuseSpike <host> <port> <user> <pass> <file> [--no-reuse] [--rest N]");
            System.exit(2);
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String user = args[2], pass = args[3], file = args[4];
        boolean reuse = !Arrays.asList(args).contains("--no-reuse");
        long rest = 0;
        for (int i = 5; i < args.length - 1; i++) {
            if (args[i].equals("--rest")) rest = Long.parseLong(args[i + 1]);
        }

        System.out.println("=== spike: reuseSession=" + reuse + " rest=" + rest + " ===");
        FtpsReuseSpike c = new FtpsReuseSpike(host, port, reuse);
        try {
            c.connectAndLogin(user, pass);
            long n = c.download(file, rest);
            System.out.println("RESULT: OK bytes=" + n);
            System.exit(0);
        } catch (Exception e) {
            System.out.println("RESULT: FAIL " + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.exit(1);
        } finally {
            c.quietClose();
        }
    }

    /** Trust-all context: the spike uses a self-signed test cert. */
    private SSLContext buildContext() throws Exception {
        TrustManager[] tm = { new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tm, new SecureRandom());
        return ctx;
    }

    void connectAndLogin(String user, String pass) throws Exception {
        ctx = buildContext();
        factory = ctx.getSocketFactory();

        rawControl = new Socket();
        rawControl.connect(new InetSocketAddress(host, port), 10_000);
        bindStreams(rawControl);

        expect(readReply(), 220);
        send("AUTH TLS");
        expect(readReply(), 234);

        // Upgrade the control connection in place.
        control = (SSLSocket) factory.createSocket(rawControl, host, port, true);
        control.setUseClientMode(true);
        control.startHandshake();
        SSLSession cs = control.getSession();
        System.out.println("[control] tls=" + cs.getProtocol()
                + " cipher=" + cs.getCipherSuite()
                + " sessionId=" + hex(cs.getId()));
        bindStreams(control);
        dumpClientSessionCache("after control handshake");

        send("USER " + user);
        int r = code(readReply());
        if (r == 331) { send("PASS " + pass); expect(readReply(), 230); }
        else if (r != 230) throw new IOException("login failed: " + r);

        send("PBSZ 0");  expect(readReply(), 200);
        send("PROT P");  expect(readReply(), 200);
        send("TYPE I");  expect(readReply(), 200);
    }

    private SSLContext ctx;

    private void dumpClientSessionCache(String when) {
        SSLSessionContext sc = ctx.getClientSessionContext();
        StringBuilder sb = new StringBuilder();
        java.util.Enumeration<byte[]> ids = sc.getIds();
        int n = 0;
        while (ids.hasMoreElements()) {
            byte[] id = ids.nextElement();
            SSLSession s = sc.getSession(id);
            sb.append("\n    id=").append(hex(id))
              .append(" peer=").append(s == null ? "?" : s.getPeerHost() + ":" + s.getPeerPort());
            n++;
        }
        System.out.println("[cache] " + when + " entries=" + n + sb);
    }

    long download(String remoteFile, long restOffset) throws Exception {
        send("PASV");
        String pasv = readReply();
        expect(pasv, 227);
        InetSocketAddress dataAddr = parsePasv(pasv);
        System.out.println("[pasv] " + dataAddr);

        // Connect the raw data socket to the data port...
        Socket rawData = new Socket();
        rawData.connect(dataAddr, 10_000);

        // ...but wrap it using the CONTROL host:port so the client session
        // cache hands us the control session to resume.
        Socket toWrap = rawData;
        int hintPort = dataAddr.getPort();
        String hintHost = dataAddr.getHostString();
        if (reuseSession) {
            hintPort = this.port;
            hintHost = this.host;
            // JSSE keys the client session cache on the WRAPPED socket's port,
            // so the hint has to be applied there too, not only in createSocket.
            toWrap = new SessionHintSocket(rawData, rawControl.getInetAddress(), this.port);
        }
        SSLSocket data = (SSLSocket) factory.createSocket(toWrap, hintHost, hintPort, true);
        data.setUseClientMode(true);

        if (restOffset > 0) {
            send("REST " + restOffset);
            expect(readReply(), 350);
        }
        send("RETR " + remoteFile);
        int pre = code(readReply());
        if (pre != 150 && pre != 125) throw new IOException("RETR refused: " + pre);

        data.startHandshake();
        SSLSession ds = data.getSession();
        boolean sameId = Arrays.equals(ds.getId(), control.getSession().getId());
        System.out.println("[data] tls=" + ds.getProtocol()
                + " sessionId=" + hex(ds.getId())
                + " sameIdAsControl=" + sameId);

        long total = 0;
        byte[] buf = new byte[32 * 1024];
        try (InputStream dIn = data.getInputStream()) {
            int n;
            while ((n = dIn.read(buf)) > 0) total += n;
        } finally {
            data.close();
        }
        int post = code(readReply());
        if (post != 226 && post != 250) throw new IOException("transfer end: " + post);
        return total;
    }

    // ---- plumbing ----

    private void bindStreams(Socket s) throws IOException {
        in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        out = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
    }

    private void send(String cmd) throws IOException {
        System.out.println("> " + (cmd.startsWith("PASS ") ? "PASS ***" : cmd));
        out.write(cmd + "\r\n");
        out.flush();
    }

    /** Reads a reply, handling RFC 959 multi-line form. */
    private String readReply() throws IOException {
        String line = in.readLine();
        if (line == null) throw new EOFException("control connection closed");
        System.out.println("< " + line);
        if (line.length() >= 4 && line.charAt(3) == '-') {
            String tag = line.substring(0, 3);
            String cur;
            while ((cur = in.readLine()) != null) {
                System.out.println("< " + cur);
                if (cur.startsWith(tag + " ")) break;
            }
        }
        return line;
    }

    private static int code(String reply) {
        return Integer.parseInt(reply.substring(0, 3));
    }

    private static void expect(String reply, int want) throws IOException {
        int got = code(reply);
        if (got != want) throw new IOException("expected " + want + " got " + got + ": " + reply);
    }

    /**
     * Port of CFtpRawTransferOpData::ParsePasvResponse (rawtransfer.cpp:327-394).
     * Scans for a delimiter, validates the six-octet run that follows, and
     * keeps scanning when a candidate does not check out -- which is what
     * makes "227 Entering passive mode (h,h,h,h,p,p)" parse correctly despite
     * the earlier spaces.
     */
    static InetSocketAddress parsePasv(String reply) throws IOException {
        final String delims = " ([{<";
        final String digits = "0123456789,";
        int pos = 2;
        String host = null;
        int port = -1;

        while (true) {
            pos = indexOfAny(reply, delims, pos + 1);
            if (pos < 0) throw new IOException("no PASV payload: " + reply);

            int end = indexOfNoneOf(reply, digits, pos + 1);
            char open = reply.charAt(pos);
            if (open == ' ') {
                if (end >= 0 && reply.charAt(end) != ' ') continue;
            } else {
                char want = open == '(' ? ')' : open == '{' ? '}' : open == '[' ? ']' : '>';
                if (end < 0 || reply.charAt(end) != want) continue;
            }

            String match = end < 0 ? reply.substring(pos + 1) : reply.substring(pos + 1, end);
            String[] tokens = match.split(",", -1);
            if (tokens.length != 6) continue;

            int[] nums = new int[6];
            boolean valid = true;
            for (int i = 0; i < 6; i++) {
                String t = tokens[i];
                if (t.isEmpty() || t.length() > 3) { valid = false; break; }
                nums[i] = Integer.parseInt(t);
                if (nums[i] > 255) { valid = false; break; }
            }
            if (!valid) continue;

            host = nums[0] + "." + nums[1] + "." + nums[2] + "." + nums[3];
            port = nums[4] * 256 + nums[5];
            break;
        }
        return new InetSocketAddress(host, port);
    }

    private static int indexOfAny(String s, String chars, int from) {
        for (int i = Math.max(from, 0); i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) return i;
        }
        return -1;
    }

    private static int indexOfNoneOf(String s, String chars, int from) {
        for (int i = Math.max(from, 0); i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) < 0) return i;
        }
        return -1;
    }

    private static String hex(byte[] b) {
        if (b == null || b.length == 0) return "<empty>";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(b.length, 8); i++) sb.append(String.format("%02x", b[i]));
        if (b.length > 8) sb.append("..");
        return sb.toString();
    }

    void quietClose() {
        try { if (control != null) control.close(); else if (rawControl != null) rawControl.close(); } catch (IOException ignored) {}
    }
}
