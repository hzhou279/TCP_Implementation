import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;

public class TCPreceiver {
  
  protected int port;
  protected InetAddress remoteIP;
  protected int remotePort;
  protected String fileName;
  protected int MTU; // maximum transmission unit in bytes
  protected int sws; // sliding window size
  protected int mode; // 1 for sender and 0 for receiver

  protected long startTime = System.nanoTime();

  protected DatagramSocket socket;
  protected FileOutputStream fos;
  protected File outFile;

  public TCPreceiver(int port, String fileName, int MTU, int sws) {
    this.port = port;
    this.fileName = fileName;
    this.MTU = MTU - 20 - 8 - 24;
    this.sws = sws;

    
    try {
      printInfo();

      // client receives initial SYN from server
      socket = new DatagramSocket(port);
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

      outFile = new File(this.fileName);
      if (!outFile.createNewFile()) {
        System.out.println("Output file exists.");
        System.exit(1);
      }
      fos = new FileOutputStream(outFile);

      // set server IP and port up
      this.remoteIP = finalPacket.getAddress();
      this.remotePort = finalPacket.getPort();

      // begin data transmission
      while (true) {
        // client receives data from server
        byte[] tcpBuf = new byte[this.MTU + TCPsegment.headerLength];
        DatagramPacket dataPacket = new DatagramPacket(tcpBuf, tcpBuf.length);
        socket.setSoTimeout(5 * 1000);
        socket.receive(dataPacket);
        TCPsegment dataTCP = new TCPsegment();
        dataTCP = dataTCP.deserialize(dataPacket.getData(), 0, tcpBuf.length); // dataBuf.length mismatch
        // output data tcp received
        dataTCP.setTime(System.nanoTime() - this.startTime);
        dataTCP.printInfo(false);

        // write data received into output file
        if (dataTCP.getData() == null)
          System.out.println("dataTCP.getData() is null");
        byte[] dataBuf = dataTCP.getData();
        fos.write(dataBuf);

        // clients sends out acknowledgement to server
        TCPsegment ackTCP = new TCPsegment(TCPsegment.ACK, 1, dataTCP.getSequenceNum() + this.MTU, dataTCP.getTimestamp());
        byte[] ackBuf = ackTCP.serialize();
        DatagramPacket ackPacket = new DatagramPacket(ackBuf, ackBuf.length, remoteIP, remotePort);
        socket.send(ackPacket);
        // output acknowledgement TCP segment sent
        ackTCP.setTime(System.nanoTime() - this.startTime);
        ackTCP.printInfo(true);
      }
      
      // socket.close();
    } catch (SocketException e) {
      System.out.println("Sender socket error.");
      e.printStackTrace();
    } catch (IOException e) {
      System.out.println("An I/O exception occurs.");
      e.printStackTrace();
    } finally {
      if (socket != null)
        socket.close();
      try {
        if (fos != null)
          fos.close();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  public void printInfo() {
    System.out.println("TCP client created with port: " + port + " fileName: " + fileName + " MTU: " + MTU + " sws: " + sws);
  }
}
