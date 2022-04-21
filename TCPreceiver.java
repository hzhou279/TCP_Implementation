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

  protected long startTime = System.nanoTime();

  public TCPreceiver(int port, String fileName, byte mtu, int sws) {
    this.port = port;
    this.fileName = fileName;
    this.mtu = mtu;
    this.sws = sws;

    
    try {
      printInfo();

      // client receives initial SYN from server
      DatagramSocket socket = new DatagramSocket(port);
      byte[] buf = new byte[TCPsegment.headerLength];
      DatagramPacket initialPacket = new DatagramPacket(buf, TCPsegment.headerLength);
      // System.out.println("Wait fot server SYN....");
      socket.receive(initialPacket);
      TCPsegment initialTCP = new TCPsegment();
      initialTCP = initialTCP.deserialize(initialPacket.getData(), 0, buf.length);
      // output first SYN received
      initialTCP.setTime(System.nanoTime() - this.startTime);
      initialTCP.printInfo(false);

      // client sends subsequent SYN + ACK back to server
      int acknowledgement = initialTCP.getSequenceNum() + 1;
      long acknowledgedTimestamp = initialTCP.getTimestamp();
      TCPsegment secondTCP = new TCPsegment((byte)(TCPsegment.SYN + TCPsegment.ACK), 0, acknowledgement, acknowledgedTimestamp);
      byte[] secondBuf = secondTCP.serialize();
      InetAddress serverIP = initialPacket.getAddress();
      int serverPort = initialPacket.getPort();
      DatagramPacket secondPacket = new DatagramPacket(secondBuf, secondBuf.length, serverIP, serverPort);
      socket.send(secondPacket);
      // output second SYN + ACK sent
      secondTCP.setTime(System.nanoTime() - this.startTime);
      secondTCP.printInfo(true);

      // client receives final ACK
      byte[] finalBuf = new byte[TCPsegment.headerLength];
      DatagramPacket finalPacket = new DatagramPacket(finalBuf, TCPsegment.headerLength);
      socket.receive(finalPacket);
      TCPsegment finalTCP = new TCPsegment();
      finalTCP = finalTCP.deserialize(finalPacket.getData(), 0, finalBuf.length);
      // output final ACK received
      finalTCP.setTime(System.nanoTime() - this.startTime);
      finalTCP.printInfo(false);

      // initial sequence number of client set to 0
      if (finalTCP.getAcknowledgement() != 1) {
        System.out.println("Acknowledgement received: " + finalTCP.getAcknowledgement());
        System.out.println("ACK from server confirmation fails.");
        System.exit(1);
      }

      System.out.println("Connection established!!!\n-----Begin data transmission-----");
      
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
