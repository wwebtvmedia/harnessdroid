# Multi-Think : Système 1 / Système 2 pour harnessDroid

Dossier de conception — sélection par scénarios et étude contradictoire.

Inspiré de *Réfléchissez vite, réfléchissez lentement* (D. Kahneman) : un
**Système 1** (S1) rapide, local, à contrôle fin, et un **Système 2** (S2)
lent, rare, qui porte l'objectif de long terme et le décompose en objectifs
courts. But opérationnel : tenir un objectif de plusieurs dizaines de minutes
avec un **budget de contexte constant par tour**, sur des petits LLM.

---

## 1. Le problème, constaté dans le code actuel

| Élément existant | Ce qu'il fait déjà | Ce qui manque |
|---|---|---|
| `AgentLoop.runTask` (FSM STATE 1/2/1B) | S1 réactif : un outil par tour | Aucune notion de but ; chaque `runTask` est isolé |
| `MemoryService` (`remember`, `recallVectors`, latent prefix) | Mémoire sémantique vectorisée | Faits sans structure de but, sans priorité, sans critère de fin |
| Compression single/ACH (`AgentLoop.performCompression`) | Sauve le budget | Résumés **jetables** : ils ne remontent vers aucune structure |
| `run_python_plan` + VM (VFS chroot, `every()`, asyncio, `call_tool`) | Exécution déterministe locale, ordonnanceur, fichiers persistants | Sous-exploité comme levier d'économie de contexte |
| `dynamicMaxContextChars` (1 500–32 000 chars) | Budget global unique | Un seul étage : but long et détail local se partagent le même espace |

Constat central : **l'objectif long n'a nulle part où vivre.** Il finit noyé
dans l'historique, puis compressé comme du détail — c'est-à-dire corrompu.

---

## 2. Critères de sélection

Les cinq axes de décision, avec pondérations utilisées en §5 :

| # | Critère | Poids | Question posée |
|---|---|---|---|
| C1 | Économie de contexte | 25 % | Le coût par tour reste-t-il borné quand la durée du but croît ? |
| C2 | Fiabilité petits modèles | 25 % | Un modèle 1–8 B peut-il tenir son rôle sans dérailler ? |
| C3 | Maintien et adaptation du but | 20 % | Le but survit-il des heures ? Est-il révisé quand la réalité contredit ? |
| C4 | Effort d'implémentation | 15 % | Volume de code nouveau, surfaces touchées |
| C5 | Parcimonie / risque de régression | 15 % | Changes minimales sur la boucle existante et les prompts |

---

## 3. Les cinq scénarios

### Scénario A — GoalStack côté Kotlin (S2 = passes LLM discrètes)

Un état persistant `goals.json` (but long + carte compacte + mission courante)
géré par du **code Kotlin** (`core/GoalStack.kt`). S2 est une fonction appelée
**sur événements** (début de session, mission terminée, N échecs consécutifs,
budget épuisé) : deux prompts fermés JSON courts — *decompose* (but → mission +
critère observable) et *consolidate* (journal → carte du but, faits à retenir,
décision continue/ajuste/abandonne). Le S1 **n'apprend rien** : ses prompts
existants (STATE 1, à côté de `memoryText`) reçoivent deux blocs de plus :

```
<GOAL> carte compacte du but long (≤300 chars, régénérée par S2) </GOAL>
<MISSION> sous-but courant + critère de réussite + tours restants </MISSION>
```

Le budget devient trois étages : A permanent (GOAL+MISSION ~500 chars),
B glissant (historique récent, compression ACH existante dont les résumés
**remontent** vers S2), C rappel vectoriel (existant).

### Scénario B — Système 2 comme outil du Système 1

Aucun état côté Kotlin. On ajoute des outils (`set_goal`, `review_goal`) au
`ToolRegistry` ; c'est le LLM qui décide quand planifier et met à jour le but
dans ses arguments. S2 = un appel d'outil parmi d'autres.

### Scénario C — La VM MicroPython comme pilote (S2 = code, pas LLM)

L'objectif long vit en **programme Python** dans le sandbox (`/plans/mission.py`),
exécuté par la VM : le plan orchestré appelle `call_tool(...)` selon sa
logique, s'adapte aux résultats par du code (`if`, boucles), planifie avec
`every()`. Le LLM n'intervient que pour écrire/réécrire le programme ou
traiter ce que le code déclare « non déterministe ». La VM devient l'agent,
le LLM son compilateur de stratégie.

### Scénario D — Double agent (deux boucles AgentLoop)

Deux instances `AgentLoop` : l'agent « S2 » porte le but long dans son propre
contexte et délègue des `runTask` courts à l'agent « S1 ». Communication par
injection de tâches et récupération de résultats.

### Scénario E — Mémoire renforcée, sans structure de but

Minimalisme : le but long n'est qu'un **fait prioritaire** répété dans le
latent prefix (`MemoryService.remember` avec boost de poids au rappel).
Pas d'état, pas d'outils, pas de S2 explicite.

---

## 4. Étude contradictoire

Méthode : pour chaque scénario, la meilleure thèse (pro) affrontée à
l'attaque la plus forte (contra), puis le verdict. Les attaques sont
formulées depuis la perspective des autres scénarios et des contraintes
réelles (petits modèles, Android, budget).

### A — GoalStack Kotlin

**Thèse.** Seule option qui garantit C1 *par construction* : la carte GOAL est
plafonnée, l'historique glisse, le rappel est à la demande — le coût par tour
est O(1) quelle que soit la durée du but. Le S1 n'apprend rien (C2 : les
petits modèles échouent surtout quand on leur ajoute des règles ; ici ils ne
voient que deux blocs de texte de plus). Les déclencheurs S2 sont du code
déterministe : pas de modèle qui « oublie » de planifier.

**Contra (attaque 1, école C).** S2 reste des passes LLM : la carte GOAL est
une **compression lossy de l'objectif** — un petit modèle qui résume un but
complexe en 300 chars peut le déformer *silencieusement*, et tout le S1
déraille alors en suivant une carte fausse. Pire : la boucle « compresser
puis suivre sa propre compression » peut amplifier la dérive à chaque cycle
S2 (allemand : le but devient le résumé du résumé).

**Contra (attaque 2, école B).** Le but vit dans un état invisible pour le
LLM : l'utilisateur ne voit que du Kotlin, le modèle ne peut pas raisonner
*sur* sa planification, et le débogage exige de lire un JSON interne. Risque
d'usine à gaz : GoalStack + MemoryService + compression ACH = trois systèmes
de mémoire redondants mal séparetés.

**Défense.** Attaque 1 : mitiger par un **critère de fin verbatim** — la
condition de réussite originale de l'utilisateur est conservée mot pour mot
(jamais compressée) et réinjectée telle quelle dans chaque passe S2 ; la carte
n'est qu'un index, pas la définition du but. Attaque 2 : GoalStack remplace
la fonction « but » des résumés ACH (les résumés deviennent son entrée) et
le recall vectoriel reste pour les faits — deux rôles, pas trois.

### B — S2 comme outil

**Thèse.** Aucune magie cachée : tout passe par les prompts et les outils
existants, testable immédiatement avec l'infra e2e actuelle ; le LLM garde
l'agentivité sur sa planification (C3).

**Contra (attaque, école A).** C'est **précisément le problème à résoudre**
que ce scénario suppose résolu : les petits modèles oublient d'appeler
l'outil de planification, sur-planifient ou bouclent dessus. Et l'objectif
reste *dans* le contexte (chaque `set_goal` écrit des tokens consommés à
chaque tour) : C1 n'est pas satisfait, on déplace le texte, on ne le borne
pas.

**Verdict.** Rejeté comme socle. Idée récupérable : un *seul* outil optionnel
de note de mission pourrait aider plus tard, mais pas comme mécanisme de
gouvernance.

### C — VM pilote

**Thèse.** Économie de contexte **maximale et vérifiable** : le pilotage est
du code, zéro token par itération de contrôle ; le déterminisme élimine la
classe d'erreurs « petit modèle » du chemin critique (C2 pour le contrôle
fin) ; et c'est la trajectoire naturelle de la VM qu'on vient d'intégrer
(ordonnanceur, `call_tool`, VFS persistant).

**Contra (attaque 1, école A).** Le programme est écrit une fois par le LLM :
**l'adaptation disparaît**. Le monde réel contredit les plans ; un pilotage
codé exécute avec assurance un plan faux. Le S2 devient un re-compilateur :
chaque surprise = re-génération complète du programme par un modèle qui a
lui-même peu d'information fraîche dans son contexte.

**Contra (attaque 2, école B).** Design actuel du pont : `call_tool` est
**bloquant** pour le thread VM (`runBlocking` côté Kotlin) — un pilotage
long-terme dans la VM exige de repenser la pompe (plan → tâche de fond,
bilan au fil de l'eau), sinon on fige l'app. Et le débogage utilisateur d'un
plan autonome bugué est difficile : le but entier déraille silencieusement
(mitigation existante : deadline VM + restart dur, mais elle *tue* la mission
au lieu de la corriger).

**Défense.** Ces attaques ne visent que C *généralisé*. Restreint aux
missions **stabilisées** (répétitives, critères observables : surveiller un
dossier, agrégater périodiquement, relancer une vérification), C est
imbattable et se greffe sur A : S2 « compile » la mission stable en plan
quand elle a réussi N fois à l'identique.

### D — Double agent

**Thèse.** Séparation conceptuelle propre ; S2 a un contexte indépendant
(rien à compresser) ; réutilisation presque intégrale d'`AgentLoop`.

**Contra (attaque, école A).** Coût : RAM (deux modèles actifs sur un
Android), latence, et **aucune économie** — le S2 porte un contexte qui
croît aussi. La communication inter-agent (protocole de délégation, reprise
d'erreur) est un nouveau sous-système entier pour reproduire ce que fait une
fonction Kotlin dans A.

**Verdict.** Rejeté sur Android (C4/C5), conservé comme référence conceptuelle.

### E — Mémoire renforcée

**Thèse.** Effort quasi nul (C4/C5 maximaux), réutilise `MemoryService`.

**Contra (attaque, tout le monde).** Le rappel par similarité n'est pas une
gouvernance : le but peut être rappelé *tard*, partiellement, ou pas du tout
si la formulation du tour s'éloigne de celle du but. Aucun critère de fin,
aucune détection de boucle. On résout « se souvenir de l'objectif », pas
« objectif long géré par objectifs courts ».

**Verdict.** Rejeté seul ; c'est déjà l'étage C du scénario A.

### Table d'attaque croisée (résumé)

| Attaque → / Scénario ↓ | A GoalStack | B outil-S2 | C VM pilote | D double agent | E mémoire |
|---|---|---|---|---|---|
| Économie de contexte réelle ? | **Oui, bornée** | Non (texte déplacé) | Oui, maximale | Non (2 contextes) | Partielle |
| Survit aux petits modèles ? | **Oui (S1 inchangé)** | Non (oublis d'appel) | Oui si mission stable | Moyen | Non (rappel incertain) |
| Dérive silencieuse du but ? | Risque (carte lossy) → critère verbatim | Faible | Risque (plan codé faux) | Faible | Élevé |
| Effort / régression | Moyen | Faible | **Élevé** (pompe à repenser) | Élevé | Quasi nul |
| Adaptation aux surprises | **Oui (S2 événementiel)** | Oui si appelé | Faible (re-générer) | Oui | Aucune |

---

## 5. Matrice de décision

Scores 1–5 (5 = meilleur) sur les critères du §2 :

| Scénario | C1 contexte (25 %) | C2 petits LLM (25 %) | C3 but/adaptation (20 %) | C4 effort (15 %) | C5 régression (15 %) | **Total /5** |
|---|---|---|---|---|---|---|
| **A GoalStack** | 5 | 4 | 4 | 3 | 4 | **4,10** |
| B outil-S2 | 2 | 2 | 3 | 4 | 4 | 2,80 |
| C VM pilote | 5 | 2,5 (contrôle 3 / adaptation 2) | 2 | 1 | 2 | 2,73 |
| D double agent | 2 | 3 | 4 | 2 | 2 | 2,65 |
| E mémoire seule | 3 | 2 | 1 | 5 | 5 | 2,95 |

Classement : **A** devant E, B, C, D — avec la nuance que E est déjà l'étage
« rappel » de A, et que C domine sur le seul critère C1 mais s'effondre sur
l'adaptation et l'effort tant qu'il n'est pas restreint aux missions stables.

## 6. Compromis retenu : A + levier VM, C comme extension bornée

**Socle : scénario A**, renforcé par trois garde-fous issus de l'étude
contradictoire :

1. **Critère de fin verbatim** : la condition de réussite originale de
   l'utilisateur est stockée mot pour mot et réinjectée intacte dans chaque
   passe S2. La carte GOAL est un index, jamais la définition du but
   (réponse à l'attaque « compression lossy »).
2. **Les résumés de compression ACH deviennent l'entrée de S2** au lieu d'être
   jetés (fusion des systèmes de mémoire, réponse à l'attaque « trois
   mémoires »).
3. **Aucun nouvel outil pour le S1** (leçon du scénario B) : le bilan de
   mission est extrait par S2, pas demandé au petit modèle.

**Extension bornée (C ciblé)** : quand une mission a réussi N fois à
l'identique, S2 peut la « compiler » en plan VM (scénario C restreint aux
missions répétitives stables) — surveillance périodique, agrégation,
vérifications. Le pilotage autonome général reste **hors périmètre** tant que
le pont `call_tool` bloquant n'est pas repensé (pompe asynchrone).

**Ce qu'on abandonne explicitement** : B (l'agentivité de planification du
petit modèle n'est pas fiable), D (coût double sans gain), E seul (rappel ≠
gouvernance).

### Feuille de route

| Phase | Contenu | Surfaces touchées |
|---|---|---|
| 1 | `core/GoalStack.kt` (goals.json, carte GOAL, mission, critère verbatim) + injection `<GOAL>/<MISSION>` dans STATE 1 + passe S2 `consolidate` en fin de `runTask` | `AgentLoop.kt` (prompts + fin de boucle), nouveau fichier |
| 2 | Mémoire : `MemoryService.remember()` en fin de mission ; résumés ACH → entrée S2 ; notes de mission dans le sandbox VFS (`/data/progress.json`) lues au démarrage de mission | `GoalStack.kt`, `MemoryService` (appel), `AgentLoop.kt` |
| 3 | Gouvernance : anti-boucle (hash des `(tool, args)`), budget de tours par mission, métriques forensic (`GOAL_DECOMPOSE`, `MISSION_DONE`, `S2_TRIGGERED`) pour mesurer les tokens économisés | `GoalStack.kt`, `ForensicLogger` (événements) |
| 4 (option) | Compilation de missions stables en plans VM ; pompe `call_tool` non bloquante | `micropython_jni.c`, `PythonEngine.kt` |

### Risques suivis

- **Dérive de carte** : mesurée en phase 3 en comparant la décision finale au
  critère verbatim ; seuil d'alerte si < 90 % de conformité.
- **Coût S2 caché** : une passe S2 ≈ 1–2 appels LLM ; plafond par session
  (ex. 10) et log du ratio tokens S1/S2.
- **Petits modèles en S2** : les prompts S2 sont fermés (JSON court, schémas
  explicites) ; en cas d'échec de parsing, **aucun changement d'état** — la
  mission continue, l'événement est loggé.

---

## 7. Peut-on trouver un optimal ?

### 7.1 Optimum global : non, structurellement

Le choix est multi-objectifs en tension (coût de contexte vs adaptation,
§2) : le théorème de Pareto exclut un optimum unique. Les pondérations de la
matrice §5 sont des choix de valeur, pas des mesures. Le bon objet est le
**front de Pareto**, pas un maximum.

### 7.2 Ce qu'on peut prouver : robustesse du socle A

En notant `w₁..w₅` les poids (Σw = 1) et les scores §5, les écarts
deviennent des fonctions des poids :

- `A − C = 1,5·w₂ + 2·(1 − w₁)` → **strictement positif dès que `w₂ > 0`** :
  A domine C pour toute pondération non dégénérée.
- `A − B = 3·w₁ + 2·w₂ + w₃ − w₄` → B ne l'emporte que si « effort » pèse
  plus de ~2/3 du total.
- `A − E = 2·w₁ + 2·w₂ + 3·w₃ − 2·w₄ − w₅` → E ne l'emporte qu'avec ~60 %
  concentrés sur effort+régression.

**Conclusion** : A est Pareto-optimal et dominant pour toute pondération
plausible — le classement n'est pas un artefact des valeurs choisies. Sur le
plan (coût × adaptation), A et C sont les deux seuls points non-dominés ;
la phase 4 (compiler les missions stables en plans VM) déplace A vers le
coin « coût minimal » de C **sans quitter le front**.

### 7.3 Les optimums réellement atteignables : paramètres du socle

Trois paramètres internes ont un optimum **calibrable empiriquement** avec le
harnais e2e existant (`run_e2e_local_llm.sh`, `run_e2e_remote_llm.sh`,
événements forensic) :

| Paramètre | Grille | Métriques d'arrêt |
|---|---|---|
| Taille de la carte GOAL | 150 / 300 / 600 chars | conformité au critère verbatim (%) vs tokens/tour |
| k événements glissants | 3 / 5 / 8 | taux de boucle (hash `(tool, args)`) vs tokens/tour |
| Seuil S2 sur échecs | 2 / 3 | tokens S2/session vs missions rattrapées |

**Protocole** : ≈20 missions longues synthétiques par point de grille ;
mesurer `tokens/tour` (p50, p95), `conformité au critère`, `taux de boucle`,
`part S2` ; puis maximiser la durée de but tenue sous contraintes
`conformité ≥ 90 %` et `part S2 ≤ 10 %` des tokens totaux.

C'est un optimum **contraint et borné** (satisficing, H. Simon) — le seul
qui ait un sens pour un système agentique : on ne cherche pas le meilleur
monde possible, on cherche le point du front de Pareto qui respecte les
contraintes de l'app (petit modèle local, budget Android).
