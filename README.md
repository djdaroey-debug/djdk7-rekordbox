# DJ DK7 — Rekordbox (Android)

Lecteur 3 decks pour vins d'honneur, alimenté par une carte Rekordbox (export Device Library).

## Compiler l'APK (comme d'habitude)
1. Crée un repo GitHub, upload TOUT le contenu de ce dossier (y compris `.github`).
2. Onglet **Actions** → **Build APK Android** → **Run workflow**.
3. Quand c'est vert : télécharge l'artifact **DJDK7-Rekordbox-debug-apk** → dézippe → `app-debug.apk`.
4. Installe sur le Mechen.

## Utilisation
1. Prépare ta carte dans Rekordbox : **Exporter vers le périphérique** en **Device Library (traditionnel)** (dossiers `PIONEER` + `Contents`).
2. Mets la carte dans le Mechen.
3. Ouvre l'appli → bouton **Carte** → choisis le dossier racine de la carte (celui qui contient `PIONEER`).
4. Les playlists se chargent ; joue le fond sur le Lecteur 1, envoie des ponctuels sur A/B.

Les réglages (nombre de lecteurs, atténuation, coupure, fondus) sont dans **Paramètres** et sont mémorisés.

## Notes techniques
- Lecture en **streaming** (léger en RAM), waveform lue depuis les fichiers ANLZ de Rekordbox.
- L'accès à la carte utilise le sélecteur de dossier Android (SAF), mémorisé entre les lancements.
