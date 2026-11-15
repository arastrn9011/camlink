"""CamLink PC alıcısı.
Kurulum:  pip install av opencv-python pyvirtualcam
USB:      python camlink_pc.py --usb
WiFi:     python camlink_pc.py --ip 192.168.1.50
Sanal kamera (Zoom/Discord vb.): --vcam  (Windows'ta OBS kurulu olmalı)
Görüntü yan/ters ise: --rotate 90 / 180 / 270, ayna için --mirror
"""
import argparse, socket, subprocess, sys
import av, cv2

ap = argparse.ArgumentParser()
ap.add_argument("--ip"); ap.add_argument("--usb", action="store_true")
ap.add_argument("--port", type=int, default=5000)
ap.add_argument("--rotate", type=int, default=0, choices=[0, 90, 180, 270])
ap.add_argument("--mirror", action="store_true")
ap.add_argument("--vcam", action="store_true")
a = ap.parse_args()

if a.usb:
    subprocess.run(["adb", "forward", f"tcp:{a.port}", f"tcp:{a.port}"], check=True)
    host = "127.0.0.1"
elif a.ip:
    host = a.ip
else:
    sys.exit("--usb veya --ip gerekli")

sock = socket.create_connection((host, a.port), timeout=5)
sock.settimeout(None)
dec = av.CodecContext.create("h264", "r")
ROT = {90: cv2.ROTATE_90_CLOCKWISE, 180: cv2.ROTATE_180, 270: cv2.ROTATE_90_COUNTERCLOCKWISE}
vcam = None
print("Bağlandı. Çıkmak için pencerede q.")
try:
    while True:
        data = sock.recv(1 << 16)
        if not data:
            print("Bağlantı kapandı (telefonda Başlat'a bastın mı?)"); break
        for pkt in dec.parse(data):
            for fr in dec.decode(pkt):
                img = fr.to_ndarray(format="bgr24")
                if a.rotate: img = cv2.rotate(img, ROT[a.rotate])
                if a.mirror: img = cv2.flip(img, 1)
                if a.vcam:
                    if vcam is None:
                        import pyvirtualcam
                        h, w = img.shape[:2]
                        vcam = pyvirtualcam.Camera(w, h, 30, fmt=pyvirtualcam.PixelFormat.BGR)
                    vcam.send(img)
                cv2.imshow("CamLink", img)
                if cv2.waitKey(1) & 0xFF == ord("q"): raise KeyboardInterrupt
except KeyboardInterrupt:
    pass
