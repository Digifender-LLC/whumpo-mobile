from pathlib import Path
import socket
def console(command):
 s=socket.create_connection(('127.0.0.1',5620),timeout=3)
 def rd():
  s.settimeout(2);b=b''
  try:
   while True:
    x=s.recv(65536)
    if not x:break
    b+=x
    if b.rstrip().endswith(b'OK') or b'KO' in b:break
  except socket.timeout:pass
  return b
 rd();s.sendall(('auth '+(Path.home()/'.emulator_console_auth_token').read_text().strip()+'\r\n').encode());assert b'OK' in rd();s.sendall((command+'\r\n').encode());r=rd();s.sendall(b'quit\r\n');s.close();return r.decode(errors='replace')
