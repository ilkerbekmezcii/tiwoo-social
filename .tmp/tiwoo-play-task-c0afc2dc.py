import os,base64,json,hashlib,requests,sys,time
from pathlib import Path
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from google.oauth2 import service_account
from google.auth.transport.requests import Request

PKG="com.earthgames.tiwoo"
LOCALE="tr-TR"
K1=base64.b64decode("Pk0Vq138Ygm9nXv+KP3Uc5vdwqmASFCWcCGOS1YX/28=")
NONCE=base64.b64decode("F5WmxLXfblB8S9mH")
AAD=b"tiwoo-play-once-20260905"
EXPECTED="2007b6fec3ec029ec69c2d3888a75ae30b112d58ebddb97ca13a9bcad8459f4f"
CT_URL="https://raw.githubusercontent.com/ilkerbekmezcii/tiwoo-social/main/.tmp/tiwoo-play-once-c0afc2dc.enc"
LISTING={"locale":"tr-TR","title":"Tiwoo","short_description":"Sosyal akış, mesajlaşma ve güçlü gizlilik kontrolleri bir arada.","full_description":"Tiwoo, insanları, fikirleri ve gündemi keşfetmek için tasarlanmış bağımsız bir sosyal platformdur.\n\nHerkese açık paylaşımları hesap oluşturmadan okuyabilir; hesabınla kendi sosyal akışını oluşturabilirsin. Gönderi paylaş, yanıtla, beğen, yeniden paylaş ve ilgini çeken hesapları takip et.\n\nTiwoo’da:\n• Herkese açık sosyal akışı keşfedebilirsin.\n• Kendi gönderilerini paylaşabilir ve konuşmalara katılabilirsin.\n• Hesapları takip ederek kişisel akışını oluşturabilirsin.\n• Beğeni, yanıt ve yeniden paylaşım özelliklerini kullanabilirsin.\n• Özel mesajlarla diğer kullanıcılarla iletişim kurabilirsin.\n• Bildirimlerle etkileşimlerini takip edebilirsin.\n• Hesap gizliliği kontrolleriyle görünürlüğünü yönetebilirsin.\n\nTiwoo; teknoloji, bilim, ekonomi ve günlük yaşam üzerine fikirlerin paylaşılabildiği sade ve modern bir sosyal deneyim sunar."}
FILES={"icon":"1pxN9UNPJMxP-MgcLH4cyVnBz8CgSPMu9","featureGraphic":"17YSd0pptz0ZnpFtH4DLVkuCO-To9hREv","phoneScreenshots":["18S7pHu_HQUFqVr8-smmfdfnz3Eebascx","1Pf-6G-Nmmt0Be9lUm-gEtKdj2yY5q7Xx","15BiOuENxZAmZnEtWINBSS0kbOMTCi7OQ","1H1KDSj6NpPiRzWENT-Mw6-4HZmrDtEOB"]}
def emit(obj):
    txt=json.dumps(obj,ensure_ascii=False)
    Path("/tmp/tiwoo-play-result.json").write_text(txt,encoding="utf-8")
    print(txt)
def fail(msg):
    emit({"ok":False,"error":msg});sys.exit(1)
def dec():
    k2s=(sys.argv[1] if len(sys.argv)>1 else os.environ.get("TIWOO_K2",""))
    if not k2s: fail("missing_k2")
    k2=base64.urlsafe_b64decode(k2s+"="*((4-len(k2s)%4)%4))
    key=bytes(a^b for a,b in zip(K1,k2))
    ct=base64.b64decode(requests.get(CT_URL,timeout=30,headers={"cache-control":"no-cache"}).text.strip())
    pt=AESGCM(key).decrypt(NONCE,ct,AAD)
    if hashlib.sha256(pt).hexdigest()!=EXPECTED: fail("credential_hash_mismatch")
    return json.loads(pt)
def rq(method,url,tok,**kw):
    h=kw.pop("headers",{});h["Authorization"]="Bearer "+tok
    r=requests.request(method,url,headers=h,timeout=120,**kw)
    if not r.ok: raise RuntimeError(f"{method} {r.status_code} {url}: {r.text[:600]}")
    return r
def drive_bytes(fid,tok):
    urls=[f"https://drive.google.com/uc?export=download&id={fid}&confirm=t",f"https://drive.usercontent.google.com/download?id={fid}&export=download&confirm=t"]
    last=None
    for u in urls:
      r=requests.get(u,headers={"Authorization":"Bearer "+tok},timeout=120,allow_redirects=True);last=r;c=r.headers.get("content-type","")
      if r.ok and c.startswith("image/"): return r.content
    raise RuntimeError(f"drive_download_failed status={last.status_code if last else 'n/a'} type={last.headers.get('content-type') if last else ''} url={last.url if last else ''}")
def main():
    sa=dec();creds=service_account.Credentials.from_service_account_info(sa,scopes=["https://www.googleapis.com/auth/androidpublisher","https://www.googleapis.com/auth/drive.readonly"]);creds.refresh(Request());tok=creds.token
    imgs={"icon":drive_bytes(FILES["icon"],tok),"featureGraphic":drive_bytes(FILES["featureGraphic"],tok),"phoneScreenshots":[drive_bytes(x,tok) for x in FILES["phoneScreenshots"]]}
    base=f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PKG}"
    edit=rq("POST",base+"/edits",tok,json={}).json();eid=edit["id"]
    try: internal=rq("GET",base+f"/edits/{eid}/tracks/internal",tok).json()
    except Exception as e: internal={"error":str(e)}
    body={"language":LOCALE,"title":LISTING["title"],"shortDescription":LISTING["short_description"],"fullDescription":LISTING["full_description"]}
    rq("PUT",base+f"/edits/{eid}/listings/{LOCALE}",tok,json=body)
    for typ in ["icon","featureGraphic","phoneScreenshots"]:
      try: rq("DELETE",base+f"/edits/{eid}/listings/{LOCALE}/{typ}",tok)
      except: pass
    upbase=f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{PKG}/edits/{eid}/listings/{LOCALE}"
    rq("POST",upbase+"/icon?uploadType=media",tok,data=imgs["icon"],headers={"Content-Type":"image/png"})
    rq("POST",upbase+"/featureGraphic?uploadType=media",tok,data=imgs["featureGraphic"],headers={"Content-Type":"image/png"})
    for b in imgs["phoneScreenshots"]: rq("POST",upbase+"/phoneScreenshots?uploadType=media",tok,data=b,headers={"Content-Type":"image/png"})
    rq("POST",base+f"/edits/{eid}:commit",tok)
    rel=[{"status":x.get("status"),"versionCodes":x.get("versionCodes"),"name":x.get("name")} for x in internal.get("releases",[])] if isinstance(internal,dict) else []
    emit({"ok":True,"packageName":PKG,"internal":rel,"listingUpdated":True,"images":{"icon":1,"featureGraphic":1,"phoneScreenshots":4},"committed":True})
if __name__=="__main__":
    try: main()
    except Exception as e: fail(str(e))
