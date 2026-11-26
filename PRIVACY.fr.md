# Politique de confidentialité

*Dernière mise à jour : 26 novembre 2026.* [English version](PRIVACY.md)

Lirelle est une application Android qui lit, affiche et fait écouter les livres, BD et livres audio d’un serveur
BookOrbit ou Komga que vous y ajoutez. Cette politique dit ce que l’application fait de vos données.

## Le développeur ne collecte rien

Lirelle n’a pas de serveur à elle. Elle ne contient ni statistiques d’usage, ni publicité, ni rapports de plantage, ni
traceurs. Rien sur vous ou sur votre usage de l’application n’est envoyé au développeur ni à qui que ce soit.

## Ce que l’application envoie à vos serveurs

Lirelle ne parle qu’aux serveurs que vous ajoutez, et n’envoie à chacun que ce qu’il faut pour s’en servir :

- **pour se connecter :** votre nom d’utilisateur et votre mot de passe, ou une clé d’API, ou la page de connexion du
  serveur lui-même (voir plus bas) ; ainsi que le nom de votre appareil (celui que vous lui avez donné dans les
  réglages d’Android, sinon son modèle), pour que le serveur puisse nommer la session ou la clé d’API qu’il crée pour
  lui ;
- **pendant que vous lisez et écoutez :** votre progression de lecture et d’écoute, vos séances de lecture et d’écoute
  (leur début, leur fin et l’avancée faite), vos signets, surlignages et notes, vos signets audio, et la note que vous
  choisissez de donner à un livre terminé ;
- **avec chaque requête :** le nom et la version de l’application, la version d’Android et le modèle de l’appareil
  (son « user agent », par exemple `Lirelle/0.8.0 (Android 16; SM-S931B)`), pour que le serveur sache quelle
  application lui parle.

Ce qu’un serveur fait de ces données relève de celui qui le gère, selon sa propre politique de confidentialité. Si
vous gérez votre propre serveur, elles restent chez vous.

**Connexion à Komga par son fournisseur d’identité (OIDC) :** la connexion se fait dans une vue web, dans
l’application, sur la page de connexion du serveur et du fournisseur d’identité qu’il utilise. L’application ne garde
que ce que le serveur lui rend pour rester connectée.

## Ce qui reste sur votre appareil

- les adresses de vos serveurs et vos profils ;
- vos sessions, chiffrées avec une clé gardée dans le keystore de votre appareil ;
- vos réglages ;
- les livres, BD et livres audio que vous téléchargez, et les couvertures gardées en cache ;
- votre historique de lecture, et la progression et les changements qui attendent le retour du réseau pour être envoyés.

Rien de tout cela ne part en sauvegarde dans le cloud ni vers un autre appareil : l’application refuse la sauvegarde
et le transfert d’appareil d’Android. Désinstaller l’application efface tout.

## Autorisations

- **Accès au réseau et état du réseau :** pour joindre vos serveurs, et pour attendre un réseau (ou le Wi-Fi, si vous
  le demandez) avant de télécharger.
- **Notifications :** pour montrer l’avancée des téléchargements et, rarement, pour vous demander de toucher la
  notification afin de reprendre la lecture.
- **Service au premier plan pour la lecture de médias, et maintien de l’appareil éveillé :** pour qu’un livre audio
  continue de jouer écran éteint ou pendant que vous utilisez d’autres applications.
- **Tâches lancées par l’utilisateur, et démarrage au lancement de l’appareil :** pour mener les téléchargements que
  vous lancez, et les reprendre après un redémarrage de l’appareil.
- **Vibration :** la courte vibration quand vous secouez le téléphone pour prolonger la minuterie de veille. Le
  capteur de mouvement est lu sur l’appareil, seulement pendant que la minuterie tourne, et rien n’en sort de
  l’appareil.

## Connexions non chiffrées

L’application accepte les serveurs joints en HTTP non chiffré, pour les serveurs d’un réseau local. Par Internet,
utilisez un serveur joint en HTTPS : sinon, ce que l’application lui envoie, mot de passe compris, peut être lu en
chemin.

## Enfants

L’application elle-même convient aux lecteurs de tout âge, et elle ne collecte rien sur personne, enfants compris.
Lirelle n’a aucun contenu à elle : elle montre ce que le serveur propose au compte connecté, et ce contenu relève de
celui qui gère le serveur et de l’utilisateur. Le propriétaire d’un serveur peut donner à un enfant un compte qui ne
voit que les bibliothèques qui lui sont destinées.

## Changements et contact

Les changements de cette politique sont datés ici, et leur historique est dans le
[dépôt public](https://github.com/lirelle-reader/lirelle) de l’application. Pour toute question, écrivez à
[conduct@tfdn.app](mailto:conduct@tfdn.app).
