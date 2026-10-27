# google_mlkit_text_recognition eklentisi tüm yazı sistemlerine referans verir; biz yalnızca
# Latin modelini paketliyoruz. Eksik Çince/Devanagari/Japonca/Korece sınıfları için uyarıyı bastır.
-dontwarn com.google.mlkit.vision.text.chinese.**
-dontwarn com.google.mlkit.vision.text.devanagari.**
-dontwarn com.google.mlkit.vision.text.japanese.**
-dontwarn com.google.mlkit.vision.text.korean.**
