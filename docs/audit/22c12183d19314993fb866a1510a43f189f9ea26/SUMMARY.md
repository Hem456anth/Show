| Build / API | Cold start ms | Idle frames (20 s) | Home scroll jank % (p90 ms) | Live TV jank % | CPU ticks idle 20 s | CPU ticks player bg 60 s | CPU ticks app away 30 s | App wake locks in bg | Audio focus in bg | PSS MB start→after 3 player cycles | Crashes | ANRs | Skipped-frame warnings | Blue px |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| baseline-api26 | 1840 | 246 | 100.0 (150) | 100.0 | 2072 | 26 | 716 | 0 | no | 88→89 | 0 | 0 | 6 | 219308 |
| baseline-api29 | 2366 | 241 | 100.0 (200) | 100.0 | 1556 | 98 | 0 | 0 | no | 188→99 | 0 | 0 | 10 | 668372 |
| baseline-api31 | 2515 | 188 | 100.0 (300) | 100.0 | 1356 | 59 | 0 | 0 | no | 144→115 | 0 | 0 | 31 | 1547972 |
| baseline-api33 | 2621 | 195 | 99.3 (200) | 96.61 | 657 | 4 | 1 | 0 | no | 161→135 | 0 | 0 | 34 | 656360 |
| baseline-api35 | 1985 | 0 | 0.0 (4950) | 0.0 | 1 | 0 | 0 | 0 | no | 16→19 | 0 | 0 | 18 | 537744 |
| candidate-api26 | 1382 | 122 | 98.6 (101) | 98.17 | 87 | 0 | 554 | 0 | no | 68→93 | 0 | 0 | 4 | 233912 |
| candidate-api29 | 2652 | 56 | 100.0 (200) | 100.0 | 392 | 4 | 0 | 0 | yes | 86→107 | 0 | 0 | 10 | 678264 |
| candidate-api31 | 2833 | 45 | 100.0 (250) | 100.0 | 269 | 7 | 0 | 0 | yes | 134→172 | 0 | 0 | 22 | 1541052 |
| candidate-api33 | 2837 | 62 | 100.0 (250) | 96.77 | 272 | 0 | 0 | 0 | no | 168→70 | 0 | 0 | 23 | 413832 |
| candidate-api35 | 3855 | 0 | 0.0 (4950) | 0.0 | 0 | 0 | 0 | 0 | no | 18→19 | 0 | 0 | 14 | 538536 |
| leakcanary-api34 | 5794 | 4 | 99.09 (300) | 100.0 | 1502 | 0 | 0 | 0 | yes | 206→195 | 0 | 1 | 19 | 652880 |

Blue pixels by screenshot:

- baseline-api26: 02-home-scrolled.png:3040, 03-live-tv.png:51656, 04-search.png:164612
- baseline-api29: 02-home-scrolled.png:456, 03-live-tv.png:51660, 04-search.png:26720, 07-player.png:589536
- baseline-api31: 02-home-scrolled.png:624, 03-live-tv.png:51656, 04-search.png:44272, 07-player.png:1451420
- baseline-api33: 02-home-scrolled.png:16448, 03-live-tv.png:19712, 04-search.png:40524, 07-player.png:579676
- baseline-api35: 04-search.png:19472, 07-player.png:258740, 08-player-resumed.png:259532
- candidate-api26: 02-home-scrolled.png:16396, 03-live-tv.png:51296, 04-search.png:164624, 08-player-resumed.png:1596
- candidate-api29: 02-home-scrolled.png:552, 03-live-tv.png:51316, 04-search.png:26728, 07-player.png:598048, 08-player-resumed.png:1620
- candidate-api31: 02-home-scrolled.png:476, 03-live-tv.png:51312, 04-search.png:44292, 07-player.png:1444972
- candidate-api33: 02-home-scrolled.png:464, 03-live-tv.png:51592, 04-search.png:40532, 07-player.png:258876, 08-player-resumed.png:62368
- candidate-api35: 04-search.png:19472, 07-player.png:259532, 08-player-resumed.png:259532
- leakcanary-api34: 02-home-scrolled.png:320, 03-live-tv.png:51592, 04-search.png:40532, 07-player.png:560436
