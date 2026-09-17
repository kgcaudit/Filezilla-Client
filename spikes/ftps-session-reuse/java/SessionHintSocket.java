import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.SocketChannel;

/**
 * Wraps a connected data-channel socket but reports the CONTROL channel's
 * endpoint, so the TLS client session cache is looked up under the control
 * connection's key and offers that session for resumption.
 *
 * Needed because JSSE's BaseSSLSocketImpl.getPort() delegates to the wrapped
 * socket, which would otherwise key the lookup on the data port and miss.
 * Conscrypt (Android) honours the host/port passed to createSocket directly,
 * so this wrapper is harmless there and makes one code path work on both.
 */
final class SessionHintSocket extends Socket {

    private final Socket delegate;
    private final InetAddress hintAddress;
    private final int hintPort;

    SessionHintSocket(Socket delegate, InetAddress hintAddress, int hintPort) {
        this.delegate = delegate;
        this.hintAddress = hintAddress;
        this.hintPort = hintPort;
    }

    // --- the hint: report the control endpoint ---
    @Override public InetAddress getInetAddress() { return hintAddress; }
    @Override public int getPort() { return hintPort; }
    @Override public SocketAddress getRemoteSocketAddress() {
        return new java.net.InetSocketAddress(hintAddress, hintPort);
    }

    // --- everything else goes to the real data socket ---
    @Override public InputStream getInputStream() throws IOException { return delegate.getInputStream(); }
    @Override public OutputStream getOutputStream() throws IOException { return delegate.getOutputStream(); }
    @Override public void close() throws IOException { delegate.close(); }
    @Override public boolean isConnected() { return delegate.isConnected(); }
    @Override public boolean isBound() { return delegate.isBound(); }
    @Override public boolean isClosed() { return delegate.isClosed(); }
    @Override public boolean isInputShutdown() { return delegate.isInputShutdown(); }
    @Override public boolean isOutputShutdown() { return delegate.isOutputShutdown(); }
    @Override public void shutdownInput() throws IOException { delegate.shutdownInput(); }
    @Override public void shutdownOutput() throws IOException { delegate.shutdownOutput(); }
    @Override public InetAddress getLocalAddress() { return delegate.getLocalAddress(); }
    @Override public int getLocalPort() { return delegate.getLocalPort(); }
    @Override public SocketAddress getLocalSocketAddress() { return delegate.getLocalSocketAddress(); }
    @Override public SocketChannel getChannel() { return delegate.getChannel(); }
    @Override public void setSoTimeout(int t) throws SocketException { delegate.setSoTimeout(t); }
    @Override public int getSoTimeout() throws SocketException { return delegate.getSoTimeout(); }
    @Override public void setTcpNoDelay(boolean on) throws SocketException { delegate.setTcpNoDelay(on); }
    @Override public boolean getTcpNoDelay() throws SocketException { return delegate.getTcpNoDelay(); }
    @Override public void setKeepAlive(boolean on) throws SocketException { delegate.setKeepAlive(on); }
    @Override public boolean getKeepAlive() throws SocketException { return delegate.getKeepAlive(); }
    @Override public void setSendBufferSize(int n) throws SocketException { delegate.setSendBufferSize(n); }
    @Override public int getSendBufferSize() throws SocketException { return delegate.getSendBufferSize(); }
    @Override public void setReceiveBufferSize(int n) throws SocketException { delegate.setReceiveBufferSize(n); }
    @Override public int getReceiveBufferSize() throws SocketException { return delegate.getReceiveBufferSize(); }
    @Override public void setSoLinger(boolean on, int l) throws SocketException { delegate.setSoLinger(on, l); }
    @Override public int getSoLinger() throws SocketException { return delegate.getSoLinger(); }
    @Override public void setReuseAddress(boolean on) throws SocketException { delegate.setReuseAddress(on); }
    @Override public boolean getReuseAddress() throws SocketException { return delegate.getReuseAddress(); }
    @Override public void setTrafficClass(int tc) throws SocketException { delegate.setTrafficClass(tc); }
    @Override public int getTrafficClass() throws SocketException { return delegate.getTrafficClass(); }
    @Override public void setOOBInline(boolean on) throws SocketException { delegate.setOOBInline(on); }
    @Override public boolean getOOBInline() throws SocketException { return delegate.getOOBInline(); }
    @Override public void sendUrgentData(int data) throws IOException { delegate.sendUrgentData(data); }
}
