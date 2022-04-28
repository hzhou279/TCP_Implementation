import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.LinkedBlockingQueue;

// import javax.xml.crypto.Data;

public class TCPsender {

  private static final double a = 0.875;
  private static final double b = 0.75;

  protected int port;
  protected InetAddress remoteIP;
  protected int remotePort;
  protected String fileName;
  protected int MTU; // maximum transmission unit in bytes
  protected int SWS; // sliding window size
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
  // protected DatagramPacket packetToRetransmit = null;
  // protected TCPsegment tcpToRetransmit = null;
  // protected boolean received = false;

  protected LinkedBlockingQueue<TCPsegment> slidingWindow;
  protected Integer ackedSeqNum;
  protected int sequenceNum;
  protected Integer curSeqNum;
  protected Integer curSegIdx;
  protected Integer curAckedSegIdx;
  protected long fileLength;

  protected Map<Integer, Integer> ackSeqNumCntMap;
  // protected Map<Integer, TimerTask> retransmitThreads;
  protected LinkedList<retransmitThread> retransmitThreads;
  protected Map<Integer, Integer> retransmitCntMap;

  protected Integer dataTransferredCnt;
  protected Integer packetsSentCnt;
  protected Integer outOfSeqPacketsDiscardedCnt;
  protected Integer inChecksumPackectsDiscardedCnt;
  protected Integer retransmitCnt;
  protected Integer dupAckCnt;

  protected Thread pThread;
  protected Thread cThread;

  class retransmitThread {
    protected TimerTask task;
    protected Integer sequenceNum;

    public retransmitThread(TimerTask task, Integer sequenceNum) {
      this.task = task;
      this.sequenceNum = sequenceNum;
    }

    public Integer getSequenceNum() {
      return this.sequenceNum;
    }

    public void cancel() {
      this.task.cancel();
    }
  }

  class Producer implements Runnable {
    protected final LinkedBlockingQueue<TCPsegment> queue;
    protected FileInputStream fis;
    protected File inputFile;

    public Producer(LinkedBlockingQueue<TCPsegment> queue, File inputFile) {
      this.queue = queue;
      this.inputFile = inputFile;
      try {
        this.fis = new FileInputStream(inputFile);
      } catch (FileNotFoundException e) {
        e.printStackTrace();
      }
    }

    @Override
    public void run() {
      try {
        this.produce();
      } catch (IOException e) {
        e.printStackTrace();
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }

    public void produce() throws IOException, InterruptedException {
      while (true) {
        synchronized (curAckedSegIdx) {
          // if (curSegIdx == curAckedSegIdx && curAckedSegIdx != 0) {
          // System.out.println("reach 79");
          // this.queue.add(new TCPsegment());
          // // return;
          // break;
          // }

          synchronized (curSegIdx) {
            if (curSegIdx >= SWS + curAckedSegIdx) {
              continue;
            }
          }
          int numBytes = MTU;

          // fail
          // synchronized(ackedSeqNum) {
          // if (fis.skip(ackedSeqNum) == -1) {
          // this.queue.add(new TCPsegment());
          // // return;
          // break;
          // }
          // }

          synchronized (curSeqNum) {
            if (fis.skip(curSeqNum - 1) == -1) {
              this.queue.add(new TCPsegment());
              // return;
              System.out.println("reach 133");
              break;
            }
          }

          if (fis.available() < MTU)
            numBytes = fis.available();

          // fail
          if (numBytes == 0) {
            this.queue.add(new TCPsegment());
            // System.out.println("reach 144");
            // return;
            break;
          }

          byte[] dataBuf = new byte[numBytes];
          fis.read(dataBuf, 0, numBytes);
          this.queue.add(new TCPsegment(TCPsegment.ACK, curSeqNum, 1, System.nanoTime(),
              numBytes, dataBuf));
          synchronized (curSeqNum) {
            curSeqNum += numBytes;
          }
          synchronized (curSegIdx) {
            curSegIdx++;
          }

          try {
            this.fis = new FileInputStream(inputFile);
          } catch (FileNotFoundException e) {
            e.printStackTrace();
          }
          // System.out.println("curSeqNum: " + curSeqNum + " ackedSeqNum: " +
          // ackedSeqNum);
          // System.out.println("curSegIdx: " + curSegIdx + " curAckedSegIdx: " +
          // curAckedSegIdx);
        }
      }

      // System.out.println("producer ends");
    }
  }

  class Consumer implements Runnable {
    protected final LinkedBlockingQueue<TCPsegment> queue;

    public Consumer(LinkedBlockingQueue<TCPsegment> queue) {
      this.queue = queue;
    }

    @Override
    public void run() {
      try {
        synchronized (queue) {
          this.consume();
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    public void consume() throws IOException {
      while (true) {
        TCPsegment dataTCP = null;
        try {
          dataTCP = this.queue.take();
          // System.out.println(dataTCP.getFlag());
          if (dataTCP.getFlag() == (byte) 0) {
            // System.out.println("consumer return");
            // return;
            break;
          }

        } catch (InterruptedException e) {
          e.printStackTrace();
        }
        if (dataTCP == null)
          System.out.println("Consumer get no TCP segments.");
        dataTCP.setTimestamp(System.nanoTime());
        byte[] tcpBuf = dataTCP.serialize();
        DatagramPacket dataPacket = new DatagramPacket(tcpBuf, tcpBuf.length, remoteIP, remotePort);
        socket.send(dataPacket);
        synchronized (dataTransferredCnt) {
          dataTransferredCnt += dataTCP.getLength();
        }
        synchronized (packetsSentCnt) {
          packetsSentCnt++;
        }
        // output data TCP segment sent
        dataTCP.setTime(System.nanoTime() - startTime);
        dataTCP.printInfo(true);

        // set up timer to retransmit the lost package
        TimerTask temp = createRetransmitTask(dataTCP, dataPacket);
        // retransmitThreads.put(dataTCP.getSequenceNum(), temp);
        synchronized (retransmitThreads) {
          retransmitThreads.add(new retransmitThread(temp, dataTCP.getSequenceNum()));
          timer.schedule(temp, TO, TO);
        }

        synchronized (ackedSeqNum) {
          if (ackedSeqNum == fileLength + 1)
            return;
        }
        // System.out.println("\nsends out: ");
        // System.out.println("curSeqNum: " + curSeqNum + " ackedSeqNum: " +
        // ackedSeqNum);
        // System.out.println("curSegIdx: " + curSegIdx + " curAckedSegIdx: " +
        // curAckedSegIdx);
      }
      // System.out.println("consumer ends");
    }
  }

  public TCPsender(int port, InetAddress remoteIP, int remotePort, String fileName, int MTU, int SWS) {
    this.port = port;
    this.remoteIP = remoteIP;
    this.remotePort = remotePort;
    this.fileName = fileName;
    this.MTU = MTU - 20 - 8 - 24; // todo: figure out exact length of IPV4 headera and udp header
    this.SWS = SWS;
    this.slidingWindow = new LinkedBlockingQueue<TCPsegment>(this.SWS);
    // this.retransmitThreads = new HashMap<Integer, TimerTask>();
    this.retransmitThreads = new LinkedList<retransmitThread>();
    this.retransmitCntMap = new HashMap<Integer, Integer>();

    this.dataTransferredCnt = 0;
    this.packetsSentCnt = 0;
    this.outOfSeqPacketsDiscardedCnt = 0;
    this.inChecksumPackectsDiscardedCnt = 0;
    this.retransmitCnt = 0;
    this.dupAckCnt = 0;

    timer = new Timer();
    try {
      // parse file into a byte array
      File inFile = new File(fileName);
      FileInputStream fis = new FileInputStream(inFile);
      this.fileLength = inFile.length();

      socket = new DatagramSocket(port);

      // server initiates a SYN
      TCPsegment initialTCP = new TCPsegment(TCPsegment.SYN, 0, System.nanoTime());
      byte[] buf = initialTCP.serialize();
      DatagramPacket initialPacket = new DatagramPacket(buf, buf.length, remoteIP, remotePort);
      socket.send(initialPacket);
      // output first SYN sent
      initialTCP.setTime(System.nanoTime() - this.startTime);
      initialTCP.printInfo(true);

      // set up timer to retransmit first SYN
      // this.tcpToRetransmit = initialTCP;
      // this.packetToRetransmit = initialPacket;
      // retransmit = this.createRetransmitTask();
      retransmit = this.createRetransmitTask(initialTCP, initialPacket);
      timer.schedule(retransmit, this.TO, this.TO);

      int acknowledgement = 0;
      while (true) {
        // server receives SYN + ACK from client
        byte[] secondBuf = new byte[TCPsegment.headerLength];
        DatagramPacket secondPacket = new DatagramPacket(secondBuf, TCPsegment.headerLength);
        // System.out.println("Wait fot client SYN + ACK....");
        socket.receive(secondPacket);

        // release timer
        // this.received = true;
        // this.tcpToRetransmit = null;
        // this.packetToRetransmit = null;

        TCPsegment secondTCP = new TCPsegment();
        secondTCP = secondTCP.deserialize(secondPacket.getData(), 0, secondBuf.length);
        short oldChecksum = secondTCP.getChecksum();
        secondTCP.setChecksum((short) 0);
        secondTCP.serialize();
        if (oldChecksum != secondTCP.getChecksum()) {
          System.out.println("SYN + ACK from client checksum failed");
          inChecksumPackectsDiscardedCnt++;
          continue;
        }

        retransmit.cancel();
        retransmit = null;
        timer.purge();
        // output second SYN + ACK received
        secondTCP.setTime(System.nanoTime() - this.startTime);
        secondTCP.printInfo(false);
        // initial sequence number of server set to 0
        if (secondTCP.getAcknowledgement() != 1) {
          System.out.println("SYN + ACK from client acknowledgement mismatch.");
          System.exit(1);
        }
        // update timeout
        computeRTT(secondTCP.getSequenceNum(), System.nanoTime(), secondTCP.getTimestamp());
        acknowledgement = secondTCP.getSequenceNum() + 1;
        break;
      }

      // server sends out final ACK to establish connection
      TCPsegment finalTCP = new TCPsegment(TCPsegment.ACK, 1, acknowledgement, System.nanoTime());
      byte[] finalBuf = finalTCP.serialize();
      DatagramPacket finalPacket = new DatagramPacket(finalBuf, finalBuf.length, remoteIP, remotePort);
      socket.send(finalPacket);
      // output final ACK sent
      finalTCP.setTime(System.nanoTime() - this.startTime);
      finalTCP.printInfo(true);

      // System.out.println();
      // begin data transmission
      // this.sequenceNum = 1;
      // while (true) {
      // // server sends data segment to client
      // // System.out.println("current sequence number is: " + sequenceNum);
      // int numBytes = this.MTU;
      // if (fis.available() < this.MTU)
      // numBytes = fis.available();
      // if (numBytes == 0)
      // break;
      // byte[] dataBuf = new byte[numBytes];
      // // if (fis.read(dataBuf, 0, numBytes) == -1)
      // // break;
      // fis.read(dataBuf, 0, numBytes);
      // TCPsegment dataTCP = new TCPsegment(TCPsegment.ACK, sequenceNum,
      // acknowledgement, System.nanoTime(),
      // numBytes, dataBuf);
      // byte[] tcpBuf = dataTCP.serialize();
      // DatagramPacket dataPacket = new DatagramPacket(tcpBuf, tcpBuf.length,
      // remoteIP, remotePort);
      // socket.send(dataPacket);
      // // output data TCP segment sent
      // dataTCP.setTime(System.nanoTime() - this.startTime);
      // dataTCP.printInfo(true);

      // // server receives acknowledgement from client
      // byte[] ackBuf = new byte[TCPsegment.headerLength];
      // DatagramPacket ackPacket = new DatagramPacket(ackBuf,
      // TCPsegment.headerLength);

      // // set retransmit parameters up
      // this.received = false;
      // this.tcpToRetransmit = dataTCP;
      // this.packetToRetransmit = dataPacket;
      // retransmit = this.createRetransmitTask();
      // timer.schedule(retransmit, this.TO, this.TO);

      // socket.receive(ackPacket);

      // // release retransmit parameters
      // this.received = true;
      // this.tcpToRetransmit = null;
      // this.packetToRetransmit = null;
      // retransmit.cancel();
      // retransmit = null;
      // timer.purge();

      // TCPsegment ackTCP = new TCPsegment();
      // ackTCP = ackTCP.deserialize(ackPacket.getData(), 0, ackBuf.length);
      // // output acknowledgement tcp received
      // ackTCP.setTime(System.nanoTime() - this.startTime);
      // ackTCP.printInfo(false);

      // // update timeout
      // computeRTT(ackTCP.getSequenceNum(), System.nanoTime(),
      // ackTCP.getTimestamp());
      // System.out.println("\nCurrent timeout: " + this.TO);

      // // check acknowledgement number
      // if (ackTCP.getAcknowledgement() != sequenceNum + numBytes) {
      // System.out.println("Acknowledgement number mismatch.");
      // System.exit(1); // todo: check if client close the connection
      // }

      // // update sequence number
      // sequenceNum += numBytes;
      // }
      this.sequenceNum = 1;
      this.curSeqNum = 1;
      this.ackedSeqNum = 0;
      this.curSegIdx = 0;
      this.curAckedSegIdx = 0;
      // slidingWindow = new LinkedBlockingQueue<TCPsegment>(this.MTU);
      Producer p = new Producer(slidingWindow, inFile);
      Consumer c = new Consumer(slidingWindow);
      pThread = new Thread(p);
      cThread = new Thread(c);
      pThread.start();
      cThread.start();

      this.ackSeqNumCntMap = new HashMap<Integer, Integer>();

      while (true) {
        // server receives acknowledgement from client
        byte[] ackBuf = new byte[TCPsegment.headerLength];
        DatagramPacket ackPacket = new DatagramPacket(ackBuf, TCPsegment.headerLength);

        socket.receive(ackPacket);

        // synchronized (this.curAckedSegIdx) {
        // this.curAckedSegIdx++;
        // }

        TCPsegment ackTCP = new TCPsegment();
        ackTCP = ackTCP.deserialize(ackPacket.getData(), 0, ackBuf.length);
        short oldChecksum = ackTCP.getChecksum();
        ackTCP.setChecksum((short)0);
        ackTCP.serialize();
        if (oldChecksum != ackTCP.getChecksum()) {
          System.out.println("An acknowledgement package from client checksum failed");
          inChecksumPackectsDiscardedCnt++;
          continue;
        }
        // output acknowledgement tcp received
        ackTCP.setTime(System.nanoTime() - this.startTime);
        ackTCP.printInfo(false);

        // update timeout
        computeRTT(ackTCP.getSequenceNum(), System.nanoTime(), ackTCP.getTimestamp());
        // System.out.println("\nCurrent timeout: " + this.TO);

        // update ackedSeqNum

        // sender receives ack number and consider it as the most updated one from
        // receiver
        // and update ackedSeqNum to be curAckedSeqNum.
        int curAckedSeqNum = ackTCP.getAcknowledgement();
        synchronized (this.curAckedSegIdx) {
          // System.out.println("\ncurAckedSeqNum: " + curAckedSeqNum + " curAckedSegIdx:
          // " +
          // curAckedSegIdx);
          // System.out.println("ackedSeqNum: " + ackedSeqNum + " curAckedSegIdx: " +
          // curAckedSegIdx + "\n");

          synchronized (this.ackedSeqNum) {
            if (this.ackedSeqNum == 0)
              this.ackedSeqNum++;
            if (curAckedSeqNum < this.ackedSeqNum)
              continue;
            this.ackedSeqNum = curAckedSeqNum;
            if ((curAckedSeqNum - 1) % this.MTU == 0) {
              this.curAckedSegIdx = (curAckedSeqNum - 1) / this.MTU;
              // TimerTask temp = this.retransmitThreads.get(curAckedSeqNum - this.MTU);
              // TimerTask temp = this.retransmitThreads.get(this.curAckedSegIdx);
              synchronized (this.retransmitThreads) {
                Iterator<retransmitThread> it = this.retransmitThreads.descendingIterator();
                while (it.hasNext()) {
                  retransmitThread curThread = it.next();
                  if (curThread.getSequenceNum() < curAckedSeqNum) {
                    curThread.cancel();
                  }
                }
                this.retransmitThreads.removeIf(cur -> (cur.getSequenceNum() < curAckedSeqNum));
                timer.purge();
              }
            }

            if (curAckedSeqNum == this.fileLength + 1) {
              // if (curAckedSeqNum == this.fileLength + 1 && curAckedSegIdx == (int)
              // (this.fileLength / this.MTU)) {
              this.curAckedSegIdx++;
              this.ackedSeqNum = (int) this.fileLength + 1;
              // TimerTask temp = this.retransmitThreads.get((int) (this.fileLength /
              // this.MTU) + 1);
              // temp.cancel();
              // timer.purge();
              synchronized (this.retransmitThreads) {
                Iterator<retransmitThread> it = this.retransmitThreads.descendingIterator();
                while (it.hasNext()) {
                  retransmitThread curThread = it.next();
                  if (curThread.getSequenceNum() < curAckedSeqNum) {
                    curThread.cancel();
                  }
                }
                this.retransmitThreads.removeIf(cur -> (cur.getSequenceNum() < curAckedSeqNum));
                timer.purge();
              }
              break;
            }
          }

          // if (curAckedSeqNum == (this.curAckedSegIdx + 1) * this.MTU + 1) {
          // System.out.println("reach 371");
          // this.curAckedSegIdx++;
          // synchronized (this.ackedSeqNum) {
          // if (this.ackedSeqNum == 0)
          // this.ackedSeqNum++;
          // this.ackedSeqNum = this.ackedSeqNum + this.MTU;
          // }

          // // cancel retransmit timer task
          // TimerTask temp = this.retransmitThreads.get(curAckedSeqNum - this.MTU);
          // temp.cancel();
          // timer.purge();
          // } else if (curAckedSeqNum == this.fileLength + 1 && curAckedSegIdx == (int)
          // (this.fileLength / this.MTU)) {
          // this.curAckedSegIdx++;
          // this.ackedSeqNum = (int) this.fileLength + 1;
          // break;
          // }
        }
        if (this.ackSeqNumCntMap.containsKey(curAckedSeqNum)) {
          // increment current ackedSeqNum count
          int curAckedSeqNumCnt = this.ackSeqNumCntMap.get(curAckedSeqNum);
          // indicate that at least one segment lost
          if (curAckedSeqNumCnt > 2) {
            // retransmit this package
            int numBytes = this.MTU;
            fis = new FileInputStream(inFile);
            if (fis.skip(curAckedSeqNum - 1) == -1) {
              // System.out.println("reach 403");
              // continue;
              break;
            }
            if (fis.available() < this.MTU)
              numBytes = fis.available();
            if (numBytes == 0) {
              // System.out.println("reach 409");
              // continue;
              break;
            }
            byte[] dataBuf = new byte[numBytes];
            fis.read(dataBuf, 0, numBytes);
            TCPsegment dataTCP = new TCPsegment(TCPsegment.ACK, curAckedSeqNum, 1, System.nanoTime(),
                numBytes, dataBuf);
            // synchronized (curSeqNum) {
            // curSeqNum = curAckedSeqNum + numBytes;
            // }
            // synchronized (curSegIdx) {
            // curSegIdx = (curAckedSeqNum - 1) % this.MTU;
            // }

            byte[] tcpBuf = dataTCP.serialize();
            DatagramPacket dataPacket = new DatagramPacket(tcpBuf, tcpBuf.length, remoteIP, remotePort);
            socket.send(dataPacket);
            // output data TCP segment sent
            dataTCP.setTime(System.nanoTime() - startTime);
            dataTCP.printInfo(true);

            this.ackSeqNumCntMap.put(curAckedSeqNum, 0);

            // set up timer to retransmit the lost package
            // this.tcpToRetransmit = initialTCP;
            // this.packetToRetransmit = initialPacket;
            // retransmit = this.createRetransmitTask();
            // timer.schedule(retransmit, this.TO, this.TO);

            // release timer
            // this.received = true;
            // this.tcpToRetransmit = null;
            // this.packetToRetransmit = null;
            // retransmit.cancel();
            // retransmit = null;
            // timer.purge();

          } else {
            this.ackSeqNumCntMap.put(curAckedSeqNum, curAckedSeqNumCnt + 1);
            dupAckCnt++;
          }
        } else {
          this.ackSeqNumCntMap.put(curAckedSeqNum, 1);
        }

        // this.ackedSeqNum = ackTCP.getAcknowledgement();
        // if (this.ackedSeqNum >= this.fileLength)
        // break;
        // // check acknowledgement number
        // if (ackTCP.getAcknowledgement() != sequenceNum + numBytes) {
        // System.out.println("Acknowledgement number mismatch.");
        // System.exit(1); // todo: check if client close the connection
        // }

        // // update sequence number
        // sequenceNum += numBytes;
      }

      // System.out.println("reach 418");
      // Main thread waits for producer and consumer to teriminate
      try {
        pThread.join();
        cThread.join();
      } catch (InterruptedException e) {
        e.printStackTrace();
      }

      // System.out.println("reach 333");

      // data transmission finished, close the file input stream
      fis.close();

      // server sends first FIN to start end connection process
      TCPsegment firstFINTCP = new TCPsegment((byte) (TCPsegment.FIN + TCPsegment.ACK), (int) this.fileLength + 1, 1,
          System.nanoTime());
      byte[] firstFINBuf = firstFINTCP.serialize();
      DatagramPacket firstFINPacket = new DatagramPacket(firstFINBuf, firstFINBuf.length, remoteIP, remotePort);
      socket.send(firstFINPacket);
      // output first FIN sent
      firstFINTCP.setTime(System.nanoTime() - this.startTime);
      firstFINTCP.printInfo(true);

      // set up timer to retransmit first FIN
      // this.received = false;
      // this.tcpToRetransmit = firstFINTCP;
      // this.packetToRetransmit = firstFINPacket;
      // retransmit = this.createRetransmitTask();
      retransmit = this.createRetransmitTask(firstFINTCP, firstFINPacket);
      timer.schedule(retransmit, this.TO, this.TO);

      // release timer parameters
      // this.received = true;
      // this.tcpToRetransmit = null;
      // this.packetToRetransmit = null;

      while (true) {
        // server receives second FIN + ACK from client
        byte[] secondFINACKBuf = new byte[TCPsegment.headerLength];
        DatagramPacket secondFINACKPacket = new DatagramPacket(secondFINACKBuf, TCPsegment.headerLength);
        socket.receive(secondFINACKPacket);

        TCPsegment secondFINACKTCP = new TCPsegment();
        secondFINACKTCP = secondFINACKTCP.deserialize(secondFINACKPacket.getData(), 0, secondFINACKBuf.length);

        short oldChecksum = secondFINACKTCP.getChecksum();
        secondFINACKTCP.setChecksum((short)0);
        secondFINACKTCP.serialize();
        if (oldChecksum != secondFINACKTCP.getChecksum()) {
          System.out.println("FIN + ACK from client checksum failed.");
          inChecksumPackectsDiscardedCnt++;
          continue;
        }

        // output second FIN + ACK received
        secondFINACKTCP.setTime(System.nanoTime() - this.startTime);
        secondFINACKTCP.printInfo(false);

        if (secondFINACKTCP.getAcknowledgement() == (int) this.fileLength + 2) {
          // System.out.println("Acknowledgement of FIN + ACK from client mismatch.");
          retransmit.cancel();
          retransmit = null;
          timer.purge();
          break;
        }
      }

      // server sends out last ACK
      TCPsegment lastACKTCP = new TCPsegment(TCPsegment.ACK, (int) this.fileLength + 2, 2, System.nanoTime());
      byte[] lastACKBuf = lastACKTCP.serialize();
      DatagramPacket lastACKPacket = new DatagramPacket(lastACKBuf, lastACKBuf.length, remoteIP, remotePort);
      socket.send(lastACKPacket);

      // output last ACK sent
      lastACKTCP.setTime(System.nanoTime() - this.startTime);
      lastACKTCP.printInfo(true);

      this.TO = 16 * this.TO;
      while (true) {
        byte[] lastOne = new byte[TCPsegment.headerLength];
        DatagramPacket lastOnePacket = new DatagramPacket(lastOne, TCPsegment.headerLength);
        try {
          socket.setSoTimeout((int) (this.TO));
          socket.receive(lastOnePacket);
          // todo: check this packet is indeed second FIN + ACK
          // todo: not here but check every SYN, FIN packet checksum
          // todo: maximum transmit times
          // todo: print out stats
          socket.send(lastACKPacket);
          // retransmit = this.createRetransmitTask(lastACKTCP, lastACKPacket);
          // timer.schedule(retransmit, 0, this.TO);
        } catch (SocketTimeoutException e) {
          // retransmit.cancel();
          retransmit = null;
          timer.purge();
          timer.cancel();
          socket.close();
          break;
        }
      }

    } catch (SocketException e) {
      System.out.println("Sender socket error.");
      e.printStackTrace();
    } catch (IOException e) {
      System.out.println("An I/O exception occurs.");
      e.printStackTrace();
    }
  }

  public void printInitInfo() {
    System.out.println("TCP sender created with port: " + port + " remoteIP: " + remoteIP.getHostAddress() +
        " remotePort: " + remotePort + " fileName: " + fileName + " MTU: " + MTU + " SWS: " + SWS);
  }

  public void printStats() {
    String out = "--------------------------\n";
    out += "- Amount of Data transferred: " + this.dataTransferredCnt;
    out += "\n- Number of packets sent: " + this.packetsSentCnt;
    out += "\n- Number of retransmissions: " + this.retransmitCnt;
    out += "\n- Number of packets discarded due to incorrect checksum: " + this.inChecksumPackectsDiscardedCnt;
    out += "\n- Number of duplicate acknowledgements: " + this.dupAckCnt;
    out += "\n--------------------------";
    System.out.println(out);
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

  public TimerTask createRetransmitTask(TCPsegment tcpToRetransmit, DatagramPacket packetToRetransmit) {
    return new TimerTask() {
      @Override
      public void run() {
        if (packetToRetransmit == null || tcpToRetransmit == null) {
          System.out.println("timer cancelled once.");
          return;
        }
        // if (received || packetToRetransmit == null || tcpToRetransmit == null) {
        // System.out.println("timer cancelled once.");
        // return;
        // }
        try {
          tcpToRetransmit.setChecksum((short) 0);
          tcpToRetransmit.setTimestamp(System.nanoTime());
          DatagramPacket temp = new DatagramPacket(tcpToRetransmit.serialize(), tcpToRetransmit.serialize().length,
              remoteIP, remotePort);
          // socket.send(packetToRetransmit);
          socket.send(temp);
          synchronized (dataTransferredCnt) {
            dataTransferredCnt += tcpToRetransmit.getLength();
          }
          synchronized (packetsSentCnt) {
            packetsSentCnt++;
          }
          synchronized (retransmitCnt) {
            retransmitCnt++;
          }
          Integer oldCnt = retransmitCntMap.get(tcpToRetransmit.getSequenceNum());
          if (oldCnt == null)
            retransmitCntMap.put(tcpToRetransmit.getSequenceNum(), 1);
          else if (oldCnt == 15) {
            System.out.println("Segment with sequence number: " + tcpToRetransmit.getSequenceNum()
                + " reaches Maximum Retransmission Count: 16");
            retransmitThreads.removeIf(cur -> (cur.getSequenceNum() == tcpToRetransmit.getSequenceNum()));
            this.cancel();
            timer.purge();
            // timer.cancel();
            printStats();
            System.exit(0);
            // printStats();
          } else
            retransmitCntMap.put(tcpToRetransmit.getSequenceNum(), oldCnt + 1);
        } catch (IOException e) {
          e.printStackTrace();
        }
        tcpToRetransmit.setTime(System.nanoTime() - startTime);
        tcpToRetransmit.printInfo(true);
      }
    };
  }
}
