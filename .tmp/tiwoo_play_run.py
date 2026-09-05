import requests, json
TOKEN_URL="https://hiyduakdyfcpibwxrtan.supabase.co/functions/v1/tiwoo-play-token-once-20260905?k=yh67ellIfEVh5y2nGBpGhD_SluT1rld7"
PKG="com.earthgames.tiwoo"
LOCALE="tr-TR"
LISTING={
"title":"Tiwoo",
"shortDescription":"Sosyal akış, mesajlaşma ve güçlü gizlilik kontrolleri bir arada.",
"fullDescription":"""Tiwoo, insanları, fikirleri ve gündemi keşfetmek için tasarlanmış bağımsız bir sosyal platformdur.

Herkese açık paylaşımları hesap oluşturmadan okuyabilir; hesabınla kendi sosyal akışını oluşturabilirsin. Gönderi paylaş, yanıtla, beğen, yeniden paylaş ve ilgini çeken hesapları takip et.

Tiwoo’da:
• Herkese açık sosyal akışı keşfedebilirsin.
• Kendi gönderilerini paylaşabilir ve konuşmalara katılabilirsin.
• Hesapları takip ederek kişisel akışını oluşturabilirsin.
• Beğeni, yanıt ve yeniden paylaşım özelliklerini kullanabilirsin.
• Özel mesajlarla diğer kullanıcılarla iletişim kurabilirsin.
• Bildirimlerle etkileşimlerini takip edebilirsin.
• Hesap gizliliği kontrolleriyle görünürlüğünü yönetebilirsin.

Tiwoo; teknoloji, bilim, ekonomi ve günlük yaşam üzerine fikirlerin paylaşılabildiği sade ve modern bir sosyal deneyim sunar."""
}
s=requests.Session()
tr=s.get(TOKEN_URL,timeout=30)
tr.raise_for_status()
token=tr.json()["access_token"]
s.headers.update({"Authorization":f"Bearer {token}"})
base=f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PKG}"
def jreq(method,url,**kw):
    r=s.request(method,url,timeout=60,**kw)
    if not r.ok:
        raise RuntimeError(f"{method} {url} -> {r.status_code}: {r.text[:1200]}")
    return r.json() if r.text else {}
edit=jreq("POST",f"{base}/edits",json={})
eid=edit["id"]
tracks=jreq("GET",f"{base}/edits/{eid}/tracks").get("tracks",[])
listings=jreq("GET",f"{base}/edits/{eid}/listings").get("listings",[])
def count_images(eid,t):
    return len(jreq("GET",f"{base}/edits/{eid}/listings/{LOCALE}/{t}").get("images",[]))
img_before={t:count_images(eid,t) for t in ("icon","featureGraphic","phoneScreenshots")}
old_tr=next((x for x in listings if x.get("language")==LOCALE),None)
jreq("PUT",f"{base}/edits/{eid}/listings/{LOCALE}",json=LISTING)
jreq("POST",f"{base}/edits/{eid}:commit",json={})
ve=jreq("POST",f"{base}/edits",json={})
vid=ve["id"]
verified=jreq("GET",f"{base}/edits/{vid}/listings/{LOCALE}")
img_after={t:count_images(vid,t) for t in ("icon","featureGraphic","phoneScreenshots")}
track_summary=[]
for t in tracks:
    rels=[]
    for r in t.get("releases",[]) or []:
        rels.append({"name":r.get("name"),"status":r.get("status"),"versionCodes":r.get("versionCodes",[]),"userFraction":r.get("userFraction")})
    track_summary.append({"track":t.get("track"),"releases":rels})
out={"ok":True,"packageName":PKG,"tracks":track_summary,"trListingBefore":None if old_tr is None else {"title":old_tr.get("title"),"shortDescription":old_tr.get("shortDescription")},"trListingAfter":{"title":verified.get("title"),"shortDescription":verified.get("shortDescription"),"fullDescriptionChars":len(verified.get("fullDescription",""))},"imageCountsBefore":img_before,"imageCountsAfter":img_after}
print(json.dumps(out,ensure_ascii=False))
