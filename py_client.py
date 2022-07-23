import socket
from time import sleep
import binascii

class ArduConn:
    def __init__(self, ip=None):
        self.reinit(ip)
        
    def reinit(self, ip=None):
        if ip == None and self.ip == None:
                self.ip = input('Enter Arduino IP: ')
        else:
            self.ip = ip
            
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.settimeout(2) #seconds
        
        try:
            self.conn()
            print('Connected')
        except Exception as e:
            print(e)

    def conn(self):
        self.sock.connect((self.ip, 80))

    def send(self, *args):
        arrayOfBytesToSend = [ord(args[0])]
        noteStr = 'sending ' + args[0]
        if len(args) > 1:
            for arg in args[1:]:
                noteStr += ', ' + str(binascii.hexlify(int(arg).to_bytes(2, 'big', signed=False)))
                arrayOfBytesToSend += int(arg).to_bytes(2, 'big', signed=False)
            print(noteStr)

        rawBytesToSend = bytearray(arrayOfBytesToSend)
        self.sock.send(rawBytesToSend)
        sleep(0.5)
        self.recv()

    def recv(self):
        try:
            msg = self.sock.recv(4096)
        except socket.timeout as e:
            print('timed out')
        except socket.error as e:
            print(e)
        else:
            if msg is not None:
                print(chr(msg[0]))
                
                for i in range(1, len(msg), 2):
                    print(int.from_bytes(msg[i:max(i+1,len(msg))], byteorder='big', signed=False))

