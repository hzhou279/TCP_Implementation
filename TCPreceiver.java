import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;

public class TCPreceiver {
  
  protected int port;
  protected String fileName;
  protected byte mtu; // maximum transmission unit in bytes
  protected int sws; // sliding window size
  protected int mode; // 1 for sender and 0 for receiver

  public TCPreceiver(int port, String fileName, byte mtu, int sws) {
    this.port = port;
    this.fileName = fileName;
    this.mtu = mtu;
    this.sws = sws;

    // client receives initial SYN from server
    try {
      DatagramSocket socket = new DatagramSocket(port);
      
      byte[] buf = new byte[TCPsegment.headerLength];
      DatagramPacket initialPacket = new DatagramPacket(buf, TCPsegment.headerLength);
      System.out.println("Wait fot server SYN....");
      socket.receive(initialPacket);
      TCPsegment initialTCP = new TCPsegment();
      initialTCP = initialTCP.deserialize(initialPacket.getData(), 0, buf.length);
      initialTCP.printInfo(false);

      // client sends subsequent SYN + ACK back to server
      int acknowledgement = initialTCP.getSequenceNum() + 1;
      TCPsegment secondTCP = new TCPsegment((byte)(TCPsegment.SYN + TCPsegment.ACK), 100, acknowledgement, System.nanoTime());
      byte[] secondBuf = secondTCP.serialize();
      InetAddress serverIP = initialPacket.getAddress();
      int serverPort = initialPacket.getPort();
      DatagramPacket secondPacket = new DatagramPacket(secondBuf, secondBuf.length, serverIP, serverPort);
      socket.send(secondPacket);
      secondTCP.printInfo(true);
      
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
    System.out.println("TCP client created with port: " + port + " fileName: " + fileName + " mtu: " + mtu + " sws: " + sws);
  }
}
