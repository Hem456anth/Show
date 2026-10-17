| Build / API | Cold start ms | Idle frames (20 s) | Home scroll jank % (p90 ms) | Live TV jank % | CPU ticks idle 20 s | CPU ticks player bg 60 s | CPU ticks app away 30 s | App wake locks in bg | Audio focus in bg | PSS MB start→after 3 player cycles | Crashes | ANRs | Skipped-frame warnings | Blue px |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| baseline-api26 | 1598 | 217 | 100.0 (200) | 100.0 | 1856 | 14 | 623 | 0 | no | 176→118 | 0 | 0 | 4 | 216888 |
| baseline-api29 | 2221 | 270 | 100.0 (150) | 100.0 | 1729 | 0 | 0 | 0 | no | 150→77 | 0 | 0 | 3 | 92640 |
| baseline-api31 | 3841 | 184 | 100.0 (400) | 100.0 | 1408 | 1 | 0 | 0 | no | 151→82 | 0 | 0 | 8 | 145044 |
| baseline-api33 | 3711 | 171 | 100.0 (300) | 97.3 | 801 | 0 | 0 | 0 | no | 128→139 | 0 | 0 | 21 | 161508 |
| baseline-api35 | 4368 | 189 | 97.12 (250) | 98.59 | 919 | 1 | 0 | 0 | no | 150→106 | 0 | 0 | 10 | 140036 |
| candidate-api26 | 1508 | 53 | 100.0 (150) | 100.0 | 1798 | 1 | 0 | 0 | no | 149→75 | 0 | 0 | 5 | 219804 |
| candidate-api29 | 2342 | 57 | 100.0 (150) | 100.0 | 417 | 4 | 1191 | 0 | yes | 96→117 | 0 | 0 | 8 | 1257668 |
| candidate-api31 | 2992 | 49 | 100.0 (250) | 100.0 | 316 | 0 | 0 | 0 | no | 126→117 | 0 | 0 | 7 | 126824 |
| candidate-api33 | 3950 | 46 | 99.15 (200) | 100.0 | 231 | 6 | 731 | 0 | yes | 143→119 | 0 | 0 | 25 | 1254972 |
| candidate-api35 | 2328 | None | None (None) | None | -2466 | 0 | 0 | 0 | no | 147→None | 0 | 0 | 0 | 0 |
| leakcanary-api34 | 4865 | 0 | 98.14 (150) | 97.75 | 0 | 2 | 83 | 0 | yes | 254→178 | 0 | 0 | 13 | 1253408 |

Blue pixels by screenshot:

- baseline-api26: 02-home-scrolled.png:620, 03-live-tv.png:51656, 04-search.png:164612
- baseline-api29: 02-home-scrolled.png:476, 03-live-tv.png:51660, 04-search.png:26720, 08-player-resumed.png:13784
- baseline-api31: 02-home-scrolled.png:476, 03-live-tv.png:51656, 04-search.png:44272, 07-player.png:26640, 08-player-resumed.png:22000
- baseline-api33: 02-home-scrolled.png:620, 03-live-tv.png:51828, 04-search.png:40524, 07-player.png:6168, 08-player-resumed.png:62368
- baseline-api35: 02-home-scrolled.png:3032, 03-live-tv.png:51836, 04-search.png:45760, 07-player.png:19704, 08-player-resumed.png:19704
- candidate-api26: 02-home-scrolled.png:2780, 03-live-tv.png:51296, 04-search.png:164624, 08-player-resumed.png:1104
- candidate-api29: 02-home-scrolled.png:552, 03-live-tv.png:51316, 04-search.png:26728, 07-player.png:589536, 08-player-resumed.png:589536
- candidate-api31: 02-home-scrolled.png:3044, 03-live-tv.png:51312, 04-search.png:44292, 07-player.png:6176, 08-player-resumed.png:22000
- candidate-api33: 02-home-scrolled.png:472, 03-live-tv.png:51592, 04-search.png:40512, 07-player.png:582720, 08-player-resumed.png:579676
- leakcanary-api34: 02-home-scrolled.png:1916, 03-live-tv.png:51592, 04-search.png:40532, 07-player.png:579684, 08-player-resumed.png:579684
