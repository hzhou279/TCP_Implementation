build:
	javac TCPend.java
	javac TCPsegment.java
	javac TCPreceiver.java
	javac TCPsender.java

clean: 
	rm ./*.class