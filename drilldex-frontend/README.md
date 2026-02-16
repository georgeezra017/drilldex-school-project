# Drilldex Frontend

De frontend van Drilldex is gebouwd met React (Vite) en vormt de UI voor een lokale marktplaats met beats, packs en kits. Gebruikers kunnen browsen, uploaden (ARTIST) en kopen (USER). Alles is lokaal ingericht voor offline beoordeling.

---

## Inhoudsopgave
1. Overzicht
2. Benodigdheden
3. Projectstructuur
4. Installatie (lokaal)
5. Configuratie (.env)
6. Starten van de frontend
7. Standaardaccounts en rollen
8. Tests
9. Opmerkingen (local-only)
10. Repository link

---

## 1. Overzicht
- **Framework:** React + Vite
- **Routing:** React Router
- **API communicatie:** Axios
- **Auth:** JWT via React Context
- **Styling:** Custom CSS + Flexbox (geen frameworks)

## 2. Benodigdheden
- **Node.js 18+**
- **npm**

## 3. Projectstructuur
```
./
├─ src/
│  ├─ components/
│  ├─ pages/
│  ├─ context/
│  └─ services/
├─ public/
├─ .env.development
└─ vite.config.js
```

## 4. Installatie (lokaal)
```bash
npm install
```

## 5. Configuratie (.env)
Controleer `.env.development`:
```env
VITE_API_BASE_URL=http://localhost:8080/api
VITE_S3_PUBLIC_BASE=http://localhost:8080/uploads
```

## 6. Starten van de frontend
```bash
npm run dev
```
Open daarna `http://localhost:5173`.

## 7. Standaardaccounts en rollen
- **ADMIN**: `admin@drilldex.com / password123`
- Voor andere rollen registreer je via de UI.

Rollen:
- **USER**: browse, kopen, downloaden
- **ARTIST**: upload en beheer eigen content
- **ADMIN**: moderatie (approve/reject)

## 8. Tests
Frontend tests zijn handmatig via de UI. Backend tests worden uitgevoerd met JUnit.

## 9. Opmerkingen (local-only)
- Geen externe services vereist.
- Alle media en licenties worden lokaal geserveerd via `/uploads`.
- Betalingen zijn gesimuleerd voor beoordeling.

## 10. Repository link
https://github.com/georgeezra017/drilldex-school-project
