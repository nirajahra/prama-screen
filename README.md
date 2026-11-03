# PRAMA SCREEN — LIVE SCREEN SHARE (ANDROID)

Staff ka phone apni screen share karta hai → Admin apne phone pe live dekhta hai. (Sirf screen — koi awaaz/mic nahi.) Internet se kahin se bhi chalta hai.

```
STAFF PHONE  ──(screen)──►  ADMIN PHONE
        server sirf connection jodta hai (video direct jaata hai)
```

## FOLDER

| FOLDER | KYA HAI |
|---|---|
| `android/` | APK ka poora source (Kotlin + WebRTC) |
| `server/` | Chhota Node.js server (connection jodne ke liye) |
| `.github/workflows/build-apk.yml` | GitHub pe APK apne aap banata hai |
| `render.yaml` | Render.com pe server free me chalane ke liye |

---

## STEP 1 — GITHUB PE UPLOAD

1. github.com pe free account banao → **New repository** → naam `prama-screen` → Create.
2. **"uploading an existing file"** pe click karo → is ZIP ke andar ka **saara content** (folders samet) drag-drop karo → **Commit**.
   - Dhyan do: `.github` folder hidden hota hai, upload me chhoot sakta hai. Agar **Actions** tab me "BUILD APK" na dikhe to: Actions → **set up a workflow yourself** → `build-apk.yml` file ka content paste karo → Commit.

## STEP 2 — SERVER CHALU KARO (FREE)

1. render.com pe GitHub se login karo → **New → Blueprint** → apni `prama-screen` repo choose karo.
2. `ADMIN_PIN` maangega → apna secret PIN daalo (jaise `7788`). Ye PIN sirf admin ko pata hona chahiye.
3. Deploy hone ke baad URL milega, jaise `https://prama-screen-server.onrender.com`.
4. Browser me khol ke dekho — `PRAMA SCREEN SERVER OK` dikhna chahiye.

> Free plan me 15 min koi use na kare to server so jata hai — pehli baar connect hone me ~1 minute lag sakta hai. Roz use karna ho to Render ka $7/month plan le lo.

## STEP 3 — APK DOWNLOAD

1. GitHub repo → **Actions** tab → **BUILD APK** (upload ke baad apne aap chalta hai, ~5-8 min).
2. Green tick aane ke baad us run pe click → neeche **Artifacts → PRAMA-SCREEN-APK** download.
3. ZIP kholo → `app-debug.apk` → phones pe install karo ("Unknown apps" allow karna padega).

## STEP 4 — USE KAISE KAREIN

**Har phone me pehli baar:** `SERVER ADDRESS` me Render wala URL daalo.

**STAFF:**
1. Naam daalo → **START SHARING** → Android puchega "Start recording/casting?" → **Start now**.
2. Notification me "YOUR SCREEN IS BEING SHARED" dikhta rahega. Wahin se STOP SHARING.

**ADMIN:**
1. `ADMIN PIN` daalo → **VIEW LIVE STAFF**.
2. List me jo staff live hai uske naam pe tap karo → screen dikhegi.
3. **END** se band.

---

## AGAR SCREEN NAHI AATI ("CONNECTION FAILED")

Kuch mobile networks (Jio/Airtel data pe kabhi-kabhi) direct connection block karte hain. Iske liye **TURN server** chahiye:

1. Metered.ca jaisi site pe free TURN account banao → wo URL, username, password dega.
2. Render → apni service → **Environment** me daalo:
   - `TURN_URL` = `turn:xxxx.metered.live:80,turn:xxxx.metered.live:443?transport=tcp`
   - `TURN_USER` = username
   - `TURN_PASS` = password
3. Save → server restart. **APK dobara banane ki zarurat nahi.**

## ZAROORI BAATEIN

- Android ka rule hai: staff ke **Start now** dabaye bina screen share ho hi nahi sakti, aur share hote waqt notification hamesha dikhti hai. Chupke se monitoring possible nahi hai (aur kanoon ke hisaab se bhi staff ki written consent le lena).
- Ek staff ki screen ek time pe ek admin dekh sakta hai.
- Video ~1280px, 15 FPS — mobile data pe ~300–800 MB/ghanta.
- Android 8 (Oreo) se upar ke phone chahiye.

## APNE COMPUTER PE BUILD (OPTIONAL)

Android Studio me `android/` folder kholo → Run. Server locally: `cd server && npm install && ADMIN_PIN=1234 npm start`, aur app me `ws://<PC-ka-IP>:8080` daalo.
