import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;

public class TCPsender {
  
  protected int port;
  protected InetAddress remoteIP;
  protected int remotePort;
  protected String fileName;
  protected byte mtu; // maximum transmission unit in bytes
  protected int sws; // sliding window size
  protected int mode; // 1 for sender and 0 for receiver
  
  public TCPsender(int port, InetAddress remoteIP, int remotePort, String fileName, byte mtu, int sws) {
    this.port = port;
    this.remoteIP = remoteIP;
    this.remotePort = remotePort;
    this.fileName = fileName;
    this.mtu = mtu;
    this.sws = sws;

    // server initiate a SYN
    try {
      DatagramSocket socket = new DatagramSocket(port);
      TCPsegment initialTCP = new TCPsegment(TCPsegment.SYN, (byte)0);
      byte[] buf = initialTCP.serialize();
      DatagramPacket initialPacket = new DatagramPacket(buf, 0, remoteIP, port);
      socket.send(initialPacket);

      
      socket.close();
    } catch (SocketException e) {
      System.out.println("Sender socket error.");
      e.printStackTrace();
    } catch (IOException e) {
      System.out.println("An I/O exception occurs.");
      e.printStackTrace();
    }
  }

  public void printInfo() {
    System.out.println("TCP sender created with port: " + port + " remoteIP: " + remoteIP + 
    " remotePort: " + remotePort + " fileName: " + fileName + " mtu: "+ mtu + " sws: " + sws);
  }
}
