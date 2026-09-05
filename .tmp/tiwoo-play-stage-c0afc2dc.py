import os,sys,json,base64,hashlib,requests
from pathlib import Path
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from google.oauth2 import service_account
from google.auth.transport.requests import Request
PKG="com.earthgames.tiwoo";LOCALE="tr-TR"
K1=base64.b64decode("Pk0Vq138Ygm9nXv+KP3Uc5vdwqmASFCWcCGOS1YX/28=");NONCE=base64.b64decode("F5WmxLXfblB8S9mH");AAD=b"tiwoo-play-once-20260905";EXPECTED="2007b6fec3ec029ec69c2d3888a75ae30b112d58ebddb97ca13a9bcad8459f4f"
CT_URL="https://raw.githubusercontent.com/ilkerbekmezcii/tiwoo-social/main/.tmp/tiwoo-play-once-c0afc2dc.enc"
LISTING={"locale":"tr-TR","title":"Tiwoo","short_description":"Sosyal akış, mesajlaşma ve güçlü gizlilik kontrolleri bir arada.","full_description":"Tiwoo, insanları, fikirleri ve gündemi keşfetmek için tasarlanmış bağımsız bir sosyal platformdur.\n\nHerkese açık paylaşımları hesap oluşturmadan okuyabilir; hesabınla kendi sosyal akışını oluşturabilirsin. Gönderi paylaş, yanıtla, beğen, yeniden paylaş ve ilgini çeken hesapları takip et.\n\nTiwoo’da:\n• Herkese açık sosyal akışı keşfedebilirsin.\n• Kendi gönderilerini paylaşabilir ve konuşmalara katılabilirsin.\n• Hesapları takip ederek kişisel akışını oluşturabilirsin.\n• Beğeni, yanıt ve yeniden paylaşım özelliklerini kullanabilirsin.\n• Özel mesajlarla diğer kullanıcılarla iletişim kurabilirsin.\n• Bildirimlerle etkileşimlerini takip edebilirsin.\n• Hesap gizliliği kontrolleriyle görünürlüğünü yönetebilirsin.\n\nTiwoo; teknoloji, bilim, ekonomi ve günlük yaşam üzerine fikirlerin paylaşılabildiği sade ve modern bir sosyal deneyim sunar."}
TOKEN="/tmp/tiwoo-play-token";EDIT="/tmp/tiwoo-play-edit";TRACK="/tmp/tiwoo-play-track.json"
def out(o): print(json.dumps(o,ensure_ascii=False))
def token(): return Path(TOKEN).read_text().strip()
def auth(k2s):
 k2=base64.urlsafe_b64decode(k2s+"="*((4-len(k2s)%4)%4));key=bytes(a^b for a,b in zip(K1,k2));ct=base64.b64decode(requests.get(CT_URL,timeout=3).text.strip());pt=AESGCM(key).decrypt(NONCE,ct,AAD);assert hashlib.sha256(pt).hexdigest()==EXPECTED;sa=json.loads(pt);c=service_account.Credentials.from_service_account_info(sa,scopes=["https://www.googleapis.com/auth/androidpublisher","https://www.googleapis.com/auth/drive.readonly"]);c.refresh(Request());Path(TOKEN).write_text(c.token);os.chmod(TOKEN,0o600);out({"ok":True,"stage":"auth"})
def asset(fid,path):
 t=token();last=None
 for u in [f"https://drive.google.com/uc?export=download&id={fid}&confirm=t",f"https://drive.usercontent.google.com/download?id={fid}&export=download&confirm=t"]:
  r=requests.get(u,headers={"Authorization":"Bearer "+t},timeout=3,allow_redirects=True);last=r;ct=r.headers.get("content-type","")
  if r.ok and ct.startswith("image/"): Path(path).write_bytes(r.content);out({"ok":True,"stage":"asset","bytes":len(r.content),"type":ct});return
 out({"ok":False,"stage":"asset","status":last.status_code if last else None,"type":last.headers.get("content-type") if last else None,"url":last.url if last else None})
def rq(m,u,**kw):
 h=kw.pop("headers",{});h["Authorization"]="Bearer "+token();r=requests.request(m,u,headers=h,timeout=3,**kw)
 if not r.ok: raise RuntimeError(f"{m} {r.status_code} {r.text[:300]}")
 return r
def edit():
 b=f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PKG}";e=rq("POST",b+"/edits",json={}).json()["id"];Path(EDIT).write_text(e)
 try: tr=rq("GET",b+f"/edits/{e}/tracks/internal").json()
 except Exception as x: tr={"error":str(x)}
 Path(TRACK).write_text(json.dumps(tr));out({"ok":True,"stage":"edit","editId":e,"internal":[{"status":r.get("status"),"versionCodes":r.get("versionCodes"),"name":r.get("name")} for r in tr.get("releases",[])]})
def listing():
 e=Path(EDIT).read_text();b=f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PKG}";body={"language":LOCALE,"title":LISTING["title"],"shortDescription":LISTING["short_description"],"fullDescription":LISTING["full_description"]};rq("PUT",b+f"/edits/{e}/listings/{LOCALE}",json=body);out({"ok":True,"stage":"listing"})
def deleteimages():
 e=Path(EDIT).read_text();b=f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PKG}"
 for ty in ["icon","featureGraphic","phoneScreenshots"]:
  try: rq("DELETE",b+f"/edits/{e}/listings/{LOCALE}/{ty}")
  except: pass
 out({"ok":True,"stage":"deleteimages"})
def upload(ty,path):
 e=Path(EDIT).read_text();u=f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{PKG}/edits/{e}/listings/{LOCALE}/{ty}?uploadType=media";rq("POST",u,data=Path(path).read_bytes(),headers={"Content-Type":"image/png"});out({"ok":True,"stage":"upload","type":ty,"bytes":Path(path).stat().st_size})
def commit():
 e=Path(EDIT).read_text();b=f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PKG}";rq("POST",b+f"/edits/{e}:commit");tr=json.loads(Path(TRACK).read_text());out({"ok":True,"stage":"commit","packageName":PKG,"internal":[{"status":r.get("status"),"versionCodes":r.get("versionCodes"),"name":r.get("name")} for r in tr.get("releases",[])]})
def clean():
 for p in [TOKEN,EDIT,TRACK,"/tmp/tiwoo-icon.png","/tmp/tiwoo-feature.png","/tmp/tiwoo-p1.png","/tmp/tiwoo-p2.png","/tmp/tiwoo-p3.png","/tmp/tiwoo-p4.png"]:
  try: Path(p).unlink()
  except: pass
 out({"ok":True,"stage":"clean"})
try:
 a=sys.argv;{"auth":lambda:auth(a[2]),"asset":lambda:asset(a[2],a[3]),"edit":edit,"listing":listing,"deleteimages":deleteimages,"upload":lambda:upload(a[2],a[3]),"commit":commit,"clean":clean}[a[1]]()
except Exception as e: out({"ok":False,"error":str(e)});sys.exit(1)
