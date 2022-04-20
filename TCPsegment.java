public class TCPsegment {

  public static final byte SYN = 0;
  public static final byte ACK = 1;
  public static final byte FIN = 2;

  protected byte flag;
  protected byte sequenceNum;
  protected byte acknowledgement;
  protected byte timestamp;
  protected byte length;
  protected short checksum;
  
  public TCPsegment(byte flag, byte sequenceNum, byte acknowledgement, byte timestamp, byte length, short checksum) {
    this.flag = flag;
    this.sequenceNum = sequenceNum;
    this.acknowledgement = acknowledgement;
    this.timestamp = timestamp;
    this.length = length;
    this.checksum = checksum;
  }

  public TCPsegment(byte flag, byte sequenceNum) {
    this.flag = flag;
    this.sequenceNum = sequenceNum;
  }

}
