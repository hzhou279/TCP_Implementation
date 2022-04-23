import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.Timer;
import java.util.TimerTask;

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
  protected double SRTT;
  protected double SDEV;
  protected long TO = 5000; // initial timeout set to 5 secs

  protected long startTime = System.nanoTime();

  protected DatagramSocket socket;

  protected Timer timer;
  protected TimerTask retransmit;
  protected DatagramPacket packetToRetransmit = null;
  protected TCPsegment tcpToRetransmit = null;
  protected boolean received = false;

  public TCPsender(int port, InetAddress remoteIP, int remotePort, String fileName, int MTU, int sws) {
    this.port = port;
    this.remoteIP = remoteIP;
    this.remotePort = remotePort;
    this.fileName = fileName;
    this.MTU = MTU - 20 - 8 - 24; // todo: figure out exact length of IPV4 headera and udp header
    this.sws = sws;

    printInfo();
    timer = new Timer();
    try {
      // parse file into a byte array
      File inFile = new File(fileName);
      FileInputStream fis = new FileInputStream(inFile);
      // byte[] fileByteArr = new byte[(int)file.length()];
      // fis.read(fileByteArr);
      // fis.close();

      socket = new DatagramSocket(port);

      // TimerTask retransmit = new TimerTask() {
      // @Override
      // public void run() {
      // // if (received || packetToRetransmit == null || tcpToRetransmit == null) {
      // // System.out.println("timer cancelled once.");
      // // timer.cancel();
      // // return;
      // // }
      // try {
      // socket.send(packetToRetransmit);
      // } catch (IOException e) {
      // e.printStackTrace();
      // }
      // tcpToRetransmit.setTime(System.nanoTime() - startTime);
      // tcpToRetransmit.printInfo(true);
      // }
      // };

      // server initiates a SYN
      TCPsegment initialTCP = new TCPsegment(TCPsegment.SYN, 0, System.nanoTime());
      byte[] buf = initialTCP.serialize();
      DatagramPacket initialPacket = new DatagramPacket(buf, buf.length, remoteIP, remotePort);
      socket.send(initialPacket);
      // output first SYN sent
      initialTCP.setTime(System.nanoTime() - this.startTime);
      initialTCP.printInfo(true);

      // set up timer to retransmit first SYN
      this.tcpToRetransmit = initialTCP;
      this.packetToRetransmit = initialPacket;
      retransmit = this.createRetransmitTask();
      timer.schedule(retransmit, this.TO, this.TO);

      // server receives SYN + ACK from client
      byte[] secondBuf = new byte[TCPsegment.headerLength];
      DatagramPacket secondPacket = new DatagramPacket(secondBuf, TCPsegment.headerLength);
      // System.out.println("Wait fot client SYN + ACK....");
      socket.receive(secondPacket);

      // release timer
      this.received = true;
      this.tcpToRetransmit = null;
      this.packetToRetransmit = null;
      retransmit.cancel();
      retransmit = null;
      timer.purge();

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

      // update timeout
      computeRTT(secondTCP.getSequenceNum(), System.nanoTime(), secondTCP.getTimestamp());
      System.out.println("\nCurrent timeout: " + this.TO);

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
        // System.out.println("current sequence number is: " + sequenceNum);
        int numBytes = this.MTU;
        if (fis.available() < this.MTU)
          numBytes = fis.available();
        if (numBytes == 0)
          break;
        byte[] dataBuf = new byte[numBytes];
        // if (fis.read(dataBuf, 0, numBytes) == -1)
        // break;
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

        // set retransmit parameters up
        this.received = false;
        this.tcpToRetransmit = dataTCP;
        this.packetToRetransmit = dataPacket;
        retransmit = this.createRetransmitTask();
        timer.schedule(retransmit, this.TO, this.TO);

        socket.receive(ackPacket);

        // release retransmit parameters
        this.received = true;
        this.tcpToRetransmit = null;
        this.packetToRetransmit = null;
        retransmit.cancel();
        retransmit = null;
        timer.purge();

        TCPsegment ackTCP = new TCPsegment();
        ackTCP = ackTCP.deserialize(ackPacket.getData(), 0, ackBuf.length);
        // output acknowledgement tcp received
        ackTCP.setTime(System.nanoTime() - this.startTime);
        ackTCP.printInfo(false);

        // update timeout
        computeRTT(ackTCP.getSequenceNum(), System.nanoTime(), ackTCP.getTimestamp());
        System.out.println("\nCurrent timeout: " + this.TO);

        // check acknowledgement number
        if (ackTCP.getAcknowledgement() != sequenceNum + numBytes) {
          System.out.println("Acknowledgement number mismatch.");
          System.exit(1); // todo: check if client close the connection
        }

        // update sequence number
        sequenceNum += numBytes;
      }

      // data transmission finished, close the file input stream
      fis.close();

      // server sends first FIN to start end connection process
      TCPsegment firstFINTCP = new TCPsegment((byte) (TCPsegment.FIN + TCPsegment.ACK), sequenceNum, System.nanoTime());
      byte[] firstFINBuf = firstFINTCP.serialize();
      DatagramPacket firstFINPacket = new DatagramPacket(firstFINBuf, firstFINBuf.length, remoteIP, remotePort);
      socket.send(firstFINPacket);
      // output first FIN sent
      firstFINTCP.setTime(System.nanoTime() - this.startTime);
      firstFINTCP.printInfo(true);

      // set up timer to retransmit first FIN
      this.received = false;
      this.tcpToRetransmit = firstFINTCP;
      this.packetToRetransmit = firstFINPacket;
      retransmit = this.createRetransmitTask();
      timer.schedule(retransmit, this.TO, this.TO);

      // server receives second FIN + ACK from client
      byte[] secondFINACKBuf = new byte[TCPsegment.headerLength];
      DatagramPacket secondFINACKPacket = new DatagramPacket(secondFINACKBuf, TCPsegment.headerLength);
      socket.receive(secondFINACKPacket);

      // release timer parameters
      this.received = true;
      this.tcpToRetransmit = null;
      this.packetToRetransmit = null;
      retransmit.cancel();
      retransmit = null;
      timer.purge();

      TCPsegment secondFINACKTCP = new TCPsegment();
      secondFINACKTCP = secondFINACKTCP.deserialize(secondFINACKPacket.getData(), 0, secondFINACKBuf.length);
      // output second FIN + ACK received
      secondFINACKTCP.setTime(System.nanoTime() - this.startTime);
      secondFINACKTCP.printInfo(false);

      if (secondFINACKTCP.getAcknowledgement() != sequenceNum + 1) {
        System.out.println("Acknowledgement of FIN + ACK from client mismatch.");
      }

      // server sends out last ACK
      TCPsegment lastACKTCP = new TCPsegment(TCPsegment.ACK, secondFINACKTCP.getSequenceNum() + 1, System.nanoTime());
      byte[] lastACKBuf = lastACKTCP.serialize();
      DatagramPacket lastACKPacket = new DatagramPacket(lastACKBuf, lastACKBuf.length, remoteIP, remotePort);
      socket.send(lastACKPacket);
      // output last ACK sent
      lastACKTCP.setTime(System.nanoTime() - this.startTime);
      lastACKTCP.printInfo(true);

      timer.cancel();
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
      this.TO = Math.round(2 * this.ERTT / 1000000);
    } else {
      this.SRTT = currentTime - acknowledgedTimestamp;
      this.SDEV = Math.abs(this.SRTT - this.ERTT);
      this.ERTT = a * this.ERTT + (1 - a) * this.SRTT;
      this.EDEV = b * this.EDEV + (1 - b) * this.SDEV;
      this.TO = Math.round((this.ERTT + 4 * this.EDEV) / 1000000);
    }
  }

  public TimerTask createRetransmitTask() {
    return new TimerTask() {
      @Override
      public void run() {
        if (received || packetToRetransmit == null || tcpToRetransmit == null) {
          System.out.println("timer cancelled once.");
          // timer.cancel();
          return;
        }
        try {
          socket.send(packetToRetransmit);
        } catch (IOException e) {
          e.printStackTrace();
        }
        tcpToRetransmit.setTime(System.nanoTime() - startTime);
        tcpToRetransmit.printInfo(true);
      }
    };
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
