# CamLink
1. Bu klasörü GitHub'a yükle (yeni repo). Actions sekmesi > Build APK > artifact "CamLink-apk" indir, telefona kur.
2. PC: pip install av opencv-python pyvirtualcam
3. Telefonda uygulamayı aç > Başlat.
4. USB: telefonda USB hata ayıklamayı aç, PC'de adb kurulu olsun > python pc/camlink_pc.py --usb
   WiFi: python pc/camlink_pc.py --ip <telefondaki IP>
