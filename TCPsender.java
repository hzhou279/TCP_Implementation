import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;

public class TCPsender {

  private static final double a = 0.875;
  private static final double b = 0.75;

  protected int port;
  protected InetAddress remoteIP;
  protected int remotePort;
  protected String fileName;
  protected int MTU; // maximum transmission unit in bytes
  protected int sws; // sliding window size
  protected int mode; // 1 for sender and 0 for receiver

  protected double ERTT;
  protected double EDEV;
  protected double TO = 5;
  protected double SRTT;
  protected double SDEV;

  protected long startTime = System.nanoTime();

  public TCPsender(int port, InetAddress remoteIP, int remotePort, String fileName, int MTU, int sws) {
    this.port = port;
    this.remoteIP = remoteIP;
    this.remotePort = remotePort;
    this.fileName = fileName;
    this.MTU = MTU - 20 - 8 - 24; // todo: figure out exact length of IPV4 headera and udp header
    this.sws = sws;

    try {
      printInfo();

      // parse file into a byte array
      File inFile = new File(fileName);
      FileInputStream fis = new FileInputStream(inFile);
      // byte[] fileByteArr = new byte[(int)file.length()];
      // fis.read(fileByteArr);
      // fis.close();

      // server initiate a SYN
      DatagramSocket socket = new DatagramSocket(port);
      TCPsegment initialTCP = new TCPsegment(TCPsegment.SYN, 0, System.nanoTime());
      byte[] buf = initialTCP.serialize();
      DatagramPacket initialPacket = new DatagramPacket(buf, buf.length, remoteIP, remotePort);
      socket.send(initialPacket);
      // output first SYN sent
      initialTCP.setTime(System.nanoTime() - this.startTime);
      initialTCP.printInfo(true);

      // server receives SYN + ACK from client
      byte[] secondBuf = new byte[TCPsegment.headerLength];
      DatagramPacket secondPacket = new DatagramPacket(secondBuf, TCPsegment.headerLength);
      // System.out.println("Wait fot client SYN + ACK....");
      socket.receive(secondPacket);
      TCPsegment secondTCP = new TCPsegment();
      secondTCP = secondTCP.deserialize(secondPacket.getData(), 0, secondBuf.length);
      // output second SYN + ACK received
      secondTCP.setTime(System.nanoTime() - this.startTime);
      secondTCP.printInfo(false);

      // initial sequence number of server set to 0
      if (secondTCP.getAcknowledgement() != 1) {
        System.out.println("SYN + ACK from client confirmation fails.");
        System.exit(1);
      }

      // server sends out final ACK to establish connection
      int acknowledgement = secondTCP.getSequenceNum() + 1;
      TCPsegment finalTCP = new TCPsegment(TCPsegment.ACK, 1, acknowledgement, System.nanoTime());
      byte[] finalBuf = finalTCP.serialize();
      DatagramPacket finalPacket = new DatagramPacket(finalBuf, finalBuf.length, remoteIP, remotePort);
      socket.send(finalPacket);
      // output final ACK sent
      finalTCP.setTime(System.nanoTime() - this.startTime);
      finalTCP.printInfo(true);

      // begin data transmission
      int sequenceNum = 1;
      while (true) {
        // server sends data segment to client
        System.out.println("current sequence number is: " + sequenceNum);
        int numBytes = this.MTU;
        if (fis.available() < this.MTU)
          numBytes = fis.available();
        if (numBytes == 0)
          break;
        byte[] dataBuf = new byte[numBytes];
        // if (fis.read(dataBuf, 0, numBytes) == -1)
        //   break;
        fis.read(dataBuf, 0, numBytes);
        TCPsegment dataTCP = new TCPsegment(TCPsegment.ACK, sequenceNum, acknowledgement, System.nanoTime(),
            numBytes, dataBuf);
        byte[] tcpBuf = dataTCP.serialize();
        DatagramPacket dataPacket = new DatagramPacket(tcpBuf, tcpBuf.length, remoteIP, remotePort);
        socket.send(dataPacket);
        // output data TCP segment sent
        dataTCP.setTime(System.nanoTime() - this.startTime);
        dataTCP.printInfo(true);

        // server receives acknowledgement from client
        byte[] ackBuf = new byte[TCPsegment.headerLength];
        DatagramPacket ackPacket = new DatagramPacket(ackBuf, TCPsegment.headerLength);
        socket.receive(ackPacket);
        TCPsegment ackTCP = new TCPsegment();
        ackTCP = ackTCP.deserialize(ackPacket.getData(), 0, ackBuf.length);
        // output acknowledgement tcp received
        ackTCP.setTime(System.nanoTime() - this.startTime);
        ackTCP.printInfo(false);

        // check acknowledgement number
        if (ackTCP.getAcknowledgement() != sequenceNum + this.MTU) {
          System.out.println("Acknowledgement number mismatch.");
          System.exit(1); // todo: check if client close the connection
        }

        // update sequence number
        sequenceNum += this.MTU;
      }

      fis.close();
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
    System.out.println("TCP sender created with port: " + port + " remoteIP: " + remoteIP.getHostAddress() +
        " remotePort: " + remotePort + " fileName: " + fileName + " MTU: " + MTU + " sws: " + sws);
  }

  public void computeRTT(int sequenceNum, long currentTime, long acknowledgedTimestamp) {
    if (sequenceNum == 0) {
      this.ERTT = currentTime - acknowledgedTimestamp;
      this.EDEV = 0;
      this.TO = 2 * this.ERTT;
    } else {
      this.SRTT = currentTime - acknowledgedTimestamp;
      this.SDEV = Math.abs(this.SRTT - this.ERTT);
      this.ERTT = a * this.ERTT + (1 - a) * this.SRTT;
      this.EDEV = b * this.EDEV + (1 - b) * this.SDEV;
      this.TO = ERTT + 4 * this.EDEV;
    }
  }

  // @Override
  // public void run() {
  // // server initiate a SYN
  // try {
  // DatagramSocket socket = new DatagramSocket(port);
  // TCPsegment initialTCP = new TCPsegment(TCPsegment.SYN, (byte) 0);
  // byte[] buf = initialTCP.serialize();
  // DatagramPacket initialPacket = new DatagramPacket(buf, 0, remoteIP, port);
  // socket.send(initialPacket);

  // socket.close();
  // } catch (SocketException e) {
  // System.out.println("Sender socket error.");
  // e.printStackTrace();
  // } catch (IOException e) {
  // System.out.println("An I/O exception occurs.");
  // e.printStackTrace();
  // }
  // }

}
