# Drilldex Backend

De backend van Drilldex is een Spring Boot REST API voor een lokale marktplaats met beats, packs en kits. De API biedt rollen (`USER`, `ARTIST`, `ADMIN`), moderatie, aankoopflows en downloadbare bestanden. Alles is lokaal ingericht zodat de applicatie offline beoordeeld kan worden.

---

## Inhoudsopgave
1. Overzicht
2. Benodigdheden
3. Projectstructuur
4. Installatie (lokaal)
5. Configuratie
6. Standaardaccounts en rollen
7. Tests
8. Opmerkingen (local-only)
9. Repository link

---

## 1. Overzicht
- **Framework:** Spring Boot
- **Database:** PostgreSQL
- **Auth:** JWT
- **Opslag:** lokale filesystem (`/uploads`, `/licenses`, `/packZips`, `/kitZips`)
- **Doel:** veilige API voor upload, moderatie, aankoop en downloads

## 2. Benodigdheden
- **Java 17**
- **PostgreSQL** (lokaal)
- **Maven wrapper** (meegeleverd in repo)
- **ffmpeg** (voor previews, indien ingeschakeld)

## 3. Projectstructuur
```
./
├─ src/main/java/com/drilldex/drillbackend   # Controllers, services, repositories
├─ src/main/resources
│  ├─ application.properties
│  └─ data.sql
├─ uploads/                                 # lokale uploads (audio/covers)
├─ licenses/                                # gegenereerde licenties (PDF)
├─ packZips/                                # pack downloads
├─ kitZips/                                 # kit downloads
└─ pom.xml
```

## 4. Installatie (lokaal)
### 4.1 Database aanmaken
```sql
CREATE DATABASE drilldex;
```

### 4.2 Configuratie aanpassen
Open `src/main/resources/application.properties` en controleer:
```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/drilldex
spring.datasource.username=<jouw_user>
spring.datasource.password=<jouw_password>
app.storage.provider=local
app.features.google-auth=false
app.features.payments.external=false
```

### 4.3 Backend starten
```bash
./mvnw spring-boot:run
```
De API start op `http://localhost:8080`.

## 5. Configuratie
Belangrijkste instellingen:
- `app.storage.provider=local` -> lokale opslag (geen cloud)
- `app.features.google-auth=false` -> Google OAuth uitgeschakeld
- `app.features.payments.external=false` -> lokale betaal-simulatie
- `data.sql` -> seed data voor demo

## 6. Standaardaccounts en rollen
- **ADMIN** wordt aangemaakt bij startup:
  - `admin@drilldex.com / password123`
- `data.sql` seedt demo gebruikers (zonder wachtwoord). Voor testen kun je nieuwe users registreren via de frontend.

Rollen:
- **USER**: browse, kopen, downloaden
- **ARTIST**: upload en beheren eigen content
- **ADMIN**: moderatie (approve/reject)

## 7. Tests
```bash
./mvnw test
```

## 8. Opmerkingen (local-only)
- Geen externe services vereist.
- Bestanden en licenties worden lokaal opgeslagen.
- Betaling is gesimuleerd voor beoordeling.

## 9. Repository link
https://github.com/georgeezra017/drilldex-school-project
