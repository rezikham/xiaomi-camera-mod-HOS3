package io.martin.hypercameramod.filters;

/**
 * Glyphs of the Leica label typeface (rounded monoline caps and digits), cut from a reference
 * raster and condensed slightly: white pixels with the anti-aliasing in the alpha channel, as
 * base64 PNG. Every glyph is {@link #CAP_HEIGHT} px tall and sits on the same baseline. Only the
 * characters the film labels need are included.
 */
final class LeicaFont {
    private LeicaFont() {
    }

    /** Height of every glyph bitmap, in pixels. */
    static final int CAP_HEIGHT = 66;

    /** Stroke width of the typeface at {@link #CAP_HEIGHT}, in pixels (the width of "I"). */
    static final int STROKE = 10;

    /** { character, base64 PNG }. */
    static final String[][] GLYPHS = {
            {"R",
                    "iVBORw0KGgoAAAANSUhEUgAAACoAAABCCAYAAAAhZERsAAADgklEQVR42u2aTWgVVxTHf3PfvPeiVpNIBRexoAUVqsVQQRe6E0F0"
                    + "J2ip+4p160bciTu7VnFTQVxEcF9BQayfm4C4EESpLpQ0UGoUNYnvzd9FzrXD4yWZm5mXjDAHLszHnXv/955zz9ecSFINaAP9wCng"
                    + "GPANi0f/Ao+AP4ErwDjQBKaA1pdekpC0StJFSW8ktbW41Jb0TtK4pAeSjkhaIakmKTZ8IKku6YzKQ+8l/S6pX1LkwSJpo6SnkhJJ"
                    + "rSUCl1hrp+7Pp8E64HtgORABjqWhKDV/Yte/Ar8BDcA5YMBelIU8WAccBbYA0/5h2cgBAr4DfgGa/kEZScbpfcCGOPBD9Ug2Z3sH"
                    + "sBrYGRc0aC+pAWzPCrQN3AJemNVICpDBaeAHYEcGoBvjjCyvAbeB0/ZhKye7YzOR+4FzdmjUwTF/XQP6Q1jfZxPkFYE0gA/Axwwb"
                    + "1Q4B2rYWFcD6yMZyWY1MvACVQQGnX6GaZKlM5oJOXwU0QASqHa2AVkB76D2VCmiS1cotZYzkgBXmQxRqQp31r+U0oc6AfgK2A0Nd"
                    + "PKdOXfsx1ClppRyKvCzfBRyeY+Ee/DTwPM7IJgFrbOC+nN7TMossDwDrO1y/bjQJ3A/Z0UPAwQLCkdhCdDKwPAImgHtxgPCv7oGN"
                    + "z7LoZ8BYvIDBizr1883lLFwZAd7GBQ7eC7oL3ARURhPqZfMf4CwwBjTKChTTs9Oem2UE6tNMQ8BJYC2WJAtN6RTRstJua4kLPExF"
                    + "Nc1jNHyfJvAzMBgH7OZ/qbg+D9WZ+bERZVT4w8D6EIV/1fJPeU3oSmAzsBfYNAdY/6wJDMcB6mLclG89R+4pMiekDWwDLgFb59nZ"
                    + "JvBTiIzGKTcvT/OTjwKXzfrMZfnqwLoQy5Sk3Lw8O5rm0mPgFbChC9B0Mu3beJF1pDqup7Iu+msJ7kph66tsXgW0AloBrYBWQCug"
                    + "FdDqP1OoI50JaPQ1gHUBu9qLBbUyeviRYyZRmkVWklC5yiCbE8wUFnTLoCg175RjJlH6fpbshb9/B/xd8CFywEsL7qJZgCYWWj90"
                    + "BmAkFXN3k+EbwHX+T2AVAbQGvAGu2a52/nTwYvkE+AMrfxyQdEHSREdZ5qSk65J+tPLImi+TLKA5a8sknZD0uqOIcFLSqKQ9vtqx"
                    + "YR8OWoXhh1TnvyQN2/tGgSDTYH1p6HFJYzZ3S9IdD1JS4zP4GLG5JVAIegAAAABJRU5ErkJggg=="},
            {"P",
                    "iVBORw0KGgoAAAANSUhEUgAAACoAAABCCAYAAAAhZERsAAAC30lEQVR42u2av2sUQRTHP/vjzojBJAqCdhaCjQQLC1srxUoQC3tB"
                    + "sbIV/wFtFVQEG7HxBwiCmtZGQQhcLAQRFK38Abn4I+Zye/e1eWOW4zS7ud3bVebBwN7u7czn3rz35s3cCyRFQA+YAi4AZ4AtgICA"
                    + "8uUz8BJ4DNwGvgBNoAMkv78lCUlTkq5LWpTU03glkfRN0idJzySdlDQpKZQUGx9Iakq6pPrIsqTLkmYkBQ4WSfslvZPUr0CbTvpD"
                    + "xr8maavBNkJgNxCbPY7DJoeJGzsE+uYfp4BzwAT2oFEh4DAJDTQETgP7gK67WTdxXDuAE0AjpL7itHoUmI1zvqiSbPNPzwC2Awfj"
                    + "gjotU2JgT5xRkwHw3FaQzeaZo2gRoGuOcmCd7zeBvXlA54Gz9mJSgJZWgWPAFWDXkCXbXUfAdB5naqZC2SjNQUTAIvA1i7LyOlPP"
                    + "BumPqNG+tTCD3QvoxxsIGUV4v+sj8w+ucxz1oB7Ug3pQD/ofgWZOHfPmo7ElE6MuoXGqTxUN2rMcMrDrUTWZ2IxOZNF6HtAp4Dgw"
                    + "OSJoOs88AuwcuL9hjTo7PgwcKmDq3XRHwEyGhH0VeJ9Ho9Ml7TTXc6YO0MqbOJfh9etJF3gRF9xpGdp+BbTqGvDd4cNP4Cbwsc6g"
                    + "AA+AJ0Ac1hDQbfrmgYu2Uw2rdqbBzV6UOuw4DywYdLcuzuT6bgOPTJMLdo6Q5F1C2xZ8owK165bSNvABuAXctXGaFpoyr0zOZuaA"
                    + "ewUsoYOgK8BrayupQ4nVjSYli8B9m45uCTbqMrNk2IzlAY2thdZh0dL7m0nl9Xp3ipeMO275rYgH9aAe1IN6UA/qQT2oB/WgGUGD"
                    + "fwU0q1aDqkGXMu7TO1WDvgGWGf7/uTv6+wG0qLBGKgTeAndYK58YnO7ADh8eslaSNn6x8sdpSTckLVmZpJMVSXOSZq08MnJlkuNu"
                    + "SGrYh22Srkr6nqpAfJqC3FQVpCR+AYGkchOYSfSxAAAAAElFTkSuQmCC"},
            {"K",
                    "iVBORw0KGgoAAAANSUhEUgAAACoAAABCCAYAAAAhZERsAAAGdUlEQVR42s2aeahUVRzHP/fOzJunaaaVRGFRYBQo4R9RFrlEBRGV"
                    + "RbZQ0Yb0/rCszGjFsKRcWrSIRAgriiwxMiikFaOUqKhoE0JRAzWX97Knz/dm+fbH/A4expm5Z+bNfXTgwMzcued872/5/pZ7kJSz"
                    + "2SXpb1VGQdIPkm6QlLHrWUm0YUY2kZS3tY+XtEJSn6SipAFJmyRd7P6HpGGS5kvabX9yo0/SNkmzbcGONoF1QLMmhLGSlknqkVS2"
                    + "WZLUL+lbSVMlRUh6SNIBA1fW0WOvpBm2cN6TxmCm09JwSUu9vWrt/7Wk8Ujab09Q609OwqsljbENBivV2EAiaZakf22PUtXeTrI9"
                    + "khbGwGggBiKOHu63U4Ex9jmm9REBGaAATAJmAyOAcp11YyALXBADCtwk28R/642MgToBeBCYaGvGDYQUAx31JFlrDBZkZJuWgNuB"
                    + "awP3KwG7Y4ZudAADwAxgHtBZJbl6YA8B64cKaMZAngY8Cow1E4gCtPARsCbbhNqiFkHGNkcBD5sTlROc0l3/C3gR2B8PkcoLwEzg"
                    + "NnPKJHXHQI853M9APm2geaAfOB+4DxgWqPIi8AbwgYEuxClLUmaP84GzAlQue4hPTeUF+15KC2jWHKgDuBeYHiBJB3Iv8AKw1dYo"
                    + "DTbKNHK8DqAPuALoMhNo5JCOM/uApcCXQM5AKi2gObPLM4F7LPSGUtEaYIUHsEQb4nY9u4yA44wvL2wQIqtV/hOwHDhgplOq5rh2"
                    + "DceXReBmo6OQsBwB3cDzwI8enSkNoJFJoWCO0wUM94CQQO7vAO97uUC5lhTaZZdFo6J5wNkBdumufwYs9qRYqKeudlARlmTMBqYG"
                    + "SFJeiHwO2G5UVGxkV62qOvIS4QHgSgOaT8iKnO31Aks8KjrcKJUcrESdFCYB9wPHBkrTqfxNF3mqvbydQF22ngfmAucF2mVsicZ8"
                    + "kyqNVN4q0KhK9TFwF3B1E3b5D7DIeDO24EC7gDpb9GN4AZgGPGEFmgLssh94GVhrdlkILXFCgZZtk6It3G+V6VzgxCajz0sewHKz"
                    + "1JKk6iwwGbjIfusFLgEuC5CIe4gdwGPAbq9+Crc5SSGiP2jgsl5lOMocKUnlkRVoj1gsz3maoV0SdeMYm42k3shsVhsVud5A06V3"
                    + "M16vGjMJYAT8Yjlmt30vtsKF2RaoKfShHBUtBn7zVN5yatbuIc/hFgHvVWfr/xeg1aDL7VgorZpJFgTmWQunEFDPt4WeajXJQrzd"
                    + "xfWbgD8M7EDaEo2qZsjaAiYAD3jNh0yaQA9aRNlnc09SWla1x/XAreb1cSsmkKR658HfAcss9GGRZhpwp0dxUYIJ7ARuBDa0EkJR"
                    + "4+H66hsljbf+fae9cDhZ0ud1+u/11tlg92WbfRcQN6G+vNlXZJ93AU+ZpOIEGnJMMNkqgZyXz7bVRmX2VTTb7Ld7vwCeNRuOGhC6"
                    + "n4XNAq4xysqlxaOqivsxsMragyEtm7LX0D3HK2VSJXxHNb0WzzclSBXPRCYCC7yMLJcmUNfEynp9o+4AsO76dKMsee2g1EJo2cB2"
                    + "AOuAlQG040xkpIXYaXZPR9qxvuT1NpcB65uQ6jhjgXHeA6ealDgP3mnF25YmwF5qRWKn12xLNXsqmgd/BbxqEk4C6xoZt1hvoGhA"
                    + "4zSBlm0KeA14N6BkcQ8yhkp3eoJxdDYtoA6Qc6Qe4BnLEUKj1rlmAqM9Nkk1cR4we91s/LrLS/mS2GCmtYj8TmGqNVPB7HUd8HpA"
                    + "He+k2gnMAabYGhmHMa2aqeTZ6yvWAw01gVOAx4HTPbCpFncFW3+7tRj/DDQBWevobuPVMhCnXYW6iPMNlVeGSZTlZ1ldlmWVgMxQ"
                    + "lMuOG982ey0FOJXLshZS6WYXhuI1uLPLbio9++89MEmF4RnGryOH6gREyShrC/C0FYqh9no5cNVQAI08sFngQ0te+ur0C6rvHQFc"
                    + "F7ew4WDs1cX2lcDHgY6VAU4KBVrkyEsBtQjQSTWm8k5+CfB7A36VZ+OHY4680lODjTbb4kmkHdJbdfa6icoBgt46zuUabEVgY2wp"
                    + "2eEaqpX39GttwVaB1sqycsBbVN7PRzWcy5UnvwKrsBOHCyTts6OQ8s6Q7pA0x5oOOTtE2K4zpDk7UDhW0nJJe7yDjGVJh+xY5nR3"
                    + "LDOWNMLAbvNOOG6VdId3zrNdIP0zpO7hR0p60k42ygT2iaQp7qDrf45ubLrTZxRuAAAAAElFTkSuQmCC"},
            {"C",
                    "iVBORw0KGgoAAAANSUhEUgAAACoAAABCCAYAAAAhZERsAAAD70lEQVR42u3aO6gcZRTA8d/OzuYGEzBBLhoRI6JBxUeIBMFHEETB"
                    + "WmIhYioL04loJQhRtNPCxtJCUlsJNr5Ajfgs1KBFJA8SJRrBGE1yd/dYzJlkckP2bu5rVtgDH+zM7Mz3nzPfOXPmnNOJCA0pMIM5"
                    + "rMeDeBL34BqUll9O4HPsxXv4J+c5jWH9p04DtEAHXdyLZ3F3Al+BnpWRIf5OwG/xCr5KnjkMQESIiG5ElDl2R8TRaE8OR8QzETGT"
                    + "PN2IICKKxo6nIuJ4njCIiGGOlZZ6nn5uH4+IXQ0FFiJiTe54OCIONCDbknruAxGxIyI6ETEjf1wbER9MAOR82PcjYlNEFEUaz+N4"
                    + "AJEG1bbUDA/hEUSBDXgsXcIkgUYqcSc2lNiEq8c4OXIsN1BnxHwd3ILZEnemr7SANjurrO16rhlsKbFlAWde39kRfIJ+OuPhIgHq"
                    + "czvYgc0LLLkebiyxMdfCqDsb4G3sye3+ErVV5jVfxQtjaLZbnHtFXVqbcAa/NTTRXAqLGUVe4wj+bRjPyDszpiF1EnS4DIY1yNEd"
                    + "8zqdy4mGYt5YqusZ15METhUtO/TDOHkJ2Pom+thfLOLiS5UaoMDHGdrVBlsvq2HDHr7D98W4a2SZfeggQf/ASwlTNmLiIrf342Wc"
                    + "KFt69JGwJb7E03get6eDP4Of8Ca+QLct0CZsNyP6XflFsRG/44dcvyUGbYLO12wfnzaOdfOt1Ee0DVpLv7Eumzcxd7kOfzVkOCp+"
                    + "KPxPZAo6BZ2CTkGnoFPQKegUdAo6BZ2CjpE7KCYQsjPpoMuSzVstjcakg9ZpnGbqaND8Kp0U0K7zmexo7KsTExMB2k2Yniqls1lV"
                    + "cf4af5mQlE43H/FdeA5bcSXOqtKRb6hK5K0myWrIbXgL2+cdvwk3Yzf2Fdqp1NW5+3V4MSH7LkzkDlLDe3BV0aKFD3FfarTOQBfz"
                    + "PEDgVtzR9hq9DbOXeKq1q+phW7kIbSxXmrxuCynG+V855qTFCMe82GCoUKXAFypVRu2eyjFea2txnYtrREuN3GZVOfsFpcSfxivA"
                    + "7lS1FIWlF20j576/4QFGlcPnSlX14cwY4dcNOVY1DlWlx38pVdWHfoN+1J210Vgwh59LHMSPuN7kNRYEPsPRItfoXucrEKF9qRlO"
                    + "412crN3OR/jGGHXzVQTtYJ9sfyvS8x9TdSMcXKJFL4cMk+EgXsMh9Jotb8W8lrd+Cy1vdWPWsWx5K5stb80mwjXZwHekxS6yQ42+"
                    + "vF4q8KK2zF6uj+0Zfm1VtWSuX8Ev1jlVS+apXI+vp6U36/YXgNawa9OvrsOjeCLDsQ0rAHkWvybgO/hQVVHu5bFztvIfSBmjGEqf"
                    + "3MwAAAAASUVORK5CYII="},
            {"F",
                    "iVBORw0KGgoAAAANSUhEUgAAACoAAABCCAYAAAAhZERsAAACpElEQVR42u2azWrUUBTHf7nJjONGQUpF3Aju3NRacKdP4AP4Am70"
                    + "RXSl7hzcuBJxq1AFvzaKClq04gMoVPGrRWmZtsn83ZxLYyl1ks7NZHEPHJIMk/DLueece84hSOpKQtKMpHlJq5IKTVZWJfUlTRlb"
                    + "F0mJpFOSnkpak5Rr8pJL+i3ptqTDkhx2Mq/2Sl9SzwFngDlgaNoWGQICzgFzDjgJJICzY1skKXHNOuAAkNJeSYD9rmXLvV1kfJ+d"
                    + "XbRRhmbNX8CrrOKNTVoyBTaBW8CnrIJFXcMW/QlcBfoAo1p0ADwBvgKdwO4iYA14DNwHNgCXjXBTAhTAPeAG0AXywFFe2HlmK1lk"
                    + "FW7umd8kAfOtN0zXrnO/elWDqbA3DBlYxU4rltV868ZTWtORHEEjaASNoBE0gu5eQ1QpSjLTUP2Vdqshquz1m1YslMuwcVdNqT1b"
                    + "dUFz4AhwHtg3xurJmX4B3gA/StWZqoB6n+kBFwL5pe8gFoErwDOz7D+wo1q0A0wFDqajwCHgklm3Y21I5ahXQPXjpNPAReCgXad1"
                    + "QJOA6sdJAs4CMwaaefdoW8JPgGngWAm+laC+N1svBZLaCCrgD/Ctanra/pDQkM7S06IFUq12OWnAP98B14DvtrGs19mZVgIC5sAH"
                    + "4DLw1rg2qiy934MHwE3g/Zi3UF/sLAPPzZKZ1RW19noHLAF3As2evH92doKs6qMdtoZWaaC0tDmOMq88E8oD+uueQZvKo7FniqAR"
                    + "NIJG0AgaQSNoBI2gEXQvoMmI/0vbAPq/KchqwL5+ZNCX1rgNdwD2vy0BC5MGfQC8ZmueXhb/acYL4CNbI+sJtH2SkzQr6ZGkgaSh"
                    + "aS5pWdJdSSfs883UjjStlD50PS7poUEWklYkXZc0bXDZJAC9/gWNb4jMkwtNhwAAAABJRU5ErkJggg=="},
            {"L",
                    "iVBORw0KGgoAAAANSUhEUgAAACYAAABCCAYAAAA7WKTiAAABnklEQVR42u3asU4UURSH8e/eHcBsbHwAKhsSX4LQ2NjY2PkIloTE"
                    + "iljQ8AL4CD4BlY0F0crORgsLOxKUWCyyy3wU3MlORMNChtkpzj+Z7E5ys/u7M3fuFOegVuqquq2eeJWZ+kl9qlLGjMr3Xg7Usbqr"
                    + "HqsXzjNRv6kv1VxwuU/Yjvq7YGqv57u6qaaCS33AMrADjAGBxDwCNbAOPAfW/jHm3pKBR+Xz7z9MrTGPW/jcF8wFxq0BI3pMXvDW"
                    + "uOAEOoUNMgELWMACFrCABSxgAQtYwAIWsIAFLGABC1jAAhawgAUsYAEL2P9S3WJsYl6t63pC16p71S1QoxbKDkG5NdEpV1XlhWFT"
                    + "4Ix5ObruCJbKb03L+agcF0n1hhkl4AtwBDyg+6JqDfwBPgPvgF9AdROsz5wDb4E94Li66+K8h8W/CrwqV/D1kK5YXR6CH8CLIe1j"
                    + "TdPAGHg2xA12BdgYImwGnOTWHrLstdbskTPgfQYOgEm5x8vENW+Ar8CHpk3rjfqz9I0tKxP1o7qlJkpP2EN1Xz1dEqpWD9UnTSPd"
                    + "JXqN0V4+Y72UAAAAAElFTkSuQmCC"},
            {"M",
                    "iVBORw0KGgoAAAANSUhEUgAAADYAAABCCAYAAAAc9iUKAAAHU0lEQVR42sWby49cRxXGf3Vv94ytOM5jPPaYQAi2YwN5OE4CKLyM"
                    + "F5HYsESISIhdxBYECxZI/ANsQOyyyB4By2yiBAcigokQCY8QxzIyiWNjx8aOx554Hv2x6K+Uopm+t7pvTbukqzuP21X1nfrOV+ec"
                    + "24WkeUlIOirpeUk39b9t4Pt7kn4qaa+fr33fyqvyVUv6gaSLklb1/21V0muSvi2pJ2k7koKkr0g6IelGAmS0rUu6LOlnkpaSAbcK"
                    + "VEj6f1rSOUlrkjbGzG9F0llJ35c0j6SDkl7WZO3HHrDnCWwFsL7v35B0dYQ9Te2CpG9WwNeBB4CBr6a24fvXgIP+vaJ8C4CAbcBT"
                    + "wE6PFRo+I187gO9WwBeB2hMMLQNGEPv9OfmzpUHVBvIYcL/HCRmfC8B24NEKuCuZXMi05B3Ap7ZopaIBBTwBHBgxak6rq4ReuU2+"
                    + "PwwsFqajkntt4/WTv2e3qoNVHzQdN4BewVWrgHVgb7JazAqYPPCRDv2MpZHvj9qXc1xkU2BhSsoE4NNWoUEhcCGZzxPARzKFo8iK"
                    + "pRbcbz8oRcfKRroN+GTCDmYFLLYDpgyFZD/K/BHg0LQ07LpiA9PwcELPqgAwWZQOTrtapZx+H7BkJas60lCm9P0JyDBrYHHAw8AX"
                    + "/HO/42qtAfd1kflSwOTVejxRtDBlf33P53NWWzr01ZmK0Qc+4TBrzZYPExooJIZ6AFjoQsMSwEKijgcsKPUE1o7P9IBVYI+B0UU4"
                    + "SkYM+xL61BNMTAkwOZo/3JWGpVZMzpceTgLWMGU/B+yz3ErxGG1H7GvrE6pjzDC2e1Oe6+pfpYEdcsQ/afLZN7CDSVA9sxVbz/CZ"
                    + "XSPxXZhwDg9m7F+y8hYD9h+Da/KP2n62awI6pkb4uLeMJuFYA66UAKYE2NUMtXvIq5abxvRshN3AI0lYNa4NgOWSKzYALmc8t5Qk"
                    + "h2QCG3ilH88w8PvJPFQC2Jw7bdtob7cIzI1s1m2Fm0OmcFu74I282IrVCbDQYNUK+Kz9ZdCSfNam4Q4Lx/YG0Ymrcyl3zpMA+4t9"
                    + "rY0GhxwWtSljTEs+6udDRkpzOjfUmgTYKeBfGcDuNB2rhj0tDXr3Afdm+tfJrdigrwPnM6R7m4Ug1hzrhtrGnEsLCxnA3jWwXklg"
                    + "wU77x8xM+aHEz0JDbWMJ+BIw3/BsbCctHnVpYOvA7zPl9g7HjeOikPj3PcA9GXNcB16zcUNpKgL8CbjYACxOeAH4vJVulI6p7x0x"
                    + "OFrU8D3gtyNSr1LAKg/w9wzZj5Ne3IRikYY7gWNe3TaZP2M3KB4Ex4E3bLllxhcz4wT3mo6M1EJi6W4xCXrVIkh/A66NlB1CqRWL"
                    + "gxz3yrXRYcn1wX6yamEk615ooWFw0PtS0odK+1gc6Iwt2GSAgf3rywzfv8VXTSGJSOKWkBOA/2GTv6ukeATgJnDCjtwWje9x5B6N"
                    + "ErPlBeCowbfJ/JveP8MmlC6aQa9aHS9nBLi7GL60SGVfDCu9bdFGBXxg6q9kGLEzsAC8niR8TbK/28o3nzxXO/fanWH5C8CLDUlu"
                    + "MWBRyi+ZjsqI9h8D7k6AbWNYEt+ZIfOXgX8yxeukaYBhijzngdtkf8krFLeL2xnW59tkfgD8GbgxKQ27VKk2nMa83zLBWNs/mgSv"
                    + "n2H4pjJH5p+3f82k/BYH/relv23f65uOcx7vSceHbTS8ALzCh1+qmcmKVY4EXrL8t1Fl0ZHIvCP/qoWGAG85Lp2Yhl1X7CbwmyQK"
                    + "aZrkvQ6KH0lkvomGN4Df2ZenMn7XF+KnTJl7WtKTOxl+C2B3RpoSo/kXvWf2Z+VjqZRf8WbdVEeMNNrP8E1KU1IZn33HZYhoGM0a"
                    + "2HXghZYiTwTwMUccbX2uOTZcTkoIM12xuNf8NRPYnhaZj+2qga1MKxxdgJHQ6VxG8hmLqYsZ2fJF9zdRmlISWAyvrjm7XWmZSOW9"
                    + "rG3fex0424WGpYCtejLLHfsKjmSOu69YQrglwOL1hqu0Xdt195VdjdoKYCQVqHPAy04vpvYL4G0XRqeW+VLA4v614qB4uQMNY93y"
                    + "XRtrcCtXLHX60+S9Q2uS+VdspN6tBqZElk95T5u2nXfgqwLGLrJi67bwJeBVB8eT+plM5bNJwafzN3Nyv/sUWv634Y316hT+tWpg"
                    + "V2ykjRIrln4jYJyV1pJMNoyRfYB/TCH7cjT/RgJImxguGm/QopgCblYMS9brY5x1kDj2qU3Cn5SOtTPqE8nguWHZGYZvUwYNNAym"
                    + "+/UxVI9gbwDHK+AXttboN7qVyO6v/Mw4msRzJyvAL5125ASwMSR71p+pG/qP++WvTd1xb0qvAT+Px5mOSXrVZ8cGyXVF0k8k3e1j"
                    + "Vb2G00O1/19J+pakN300a1xblfS2pO/5DFuv5XRTz2PskPQjSefdxyDp75yPWdUkB+OO+QzZhq8VSc/4rFhIjj21nfmKz31V0slN"
                    + "jkfFn9+S9B1J2zzpvj/fdGwrGm6HpB9KOpOcJzvtc2Y9Sb3/AppjYFh2Akk6AAAAAElFTkSuQmCC"},
            {"0",
                    "iVBORw0KGgoAAAANSUhEUgAAACgAAABCCAYAAAAlkZRRAAAC10lEQVR42u2av2sUQRTHP7s3d8EkIqSJBkRENIidIIIiWAjaRrBQ"
                    + "EBv/BC3trPwDxB+FoIIiklpilUZTCiJKQAQJikKCJNGYmL2vxb5JhiNcdi+X2xTz4HHH7c3OZ75v3uxx7yWSEnITkAINYAXYDZwH"
                    + "rgAngGGgRvdsDngHTADPgRnAAU3gn/9SEgAmwReOAbeA00C/uaO7lgFL5jPAfQOdNyFWc9mk1NxJqkkak/RRvbcVSc8kjUjCeNYA"
                    + "a5Lqki5J+mwDMklN8+0yf/8s+OylpCFJiSSHpIYRj0r6EMD12kLQO5J2Sap7KfdIelwhXKuiC5LOSSK1zXgGuBwkS1Xm5x4ErgOD"
                    + "KTAAjFmWqmJAf9wBnAKOOGAIOBlcTNoMVBdB0k1U7AcuOOCQQW4W3qTH6vYBxx0w0mY1oaozwKSd8v5A70S1FWDUHgLtFKwBe50l"
                    + "SZHQjQM3AuhOAOvAMnAWeAAcbrOtHDCSlthXiwZVawl5GZdN/B34ViCjG65keJr2DNUWQpzZa1oko13J9G/1To4Qv7gi45Wywy0C"
                    + "RsAIGAEjYASMgBEwAkbACBgBI2AEjIARMAJGwAgYASNgBKzKyvzLn7BePui06JgE9ykyPnEUr8EpUL3Zgfp+UTXyWklWJMKuhBIN"
                    + "UzxrAS6r4Cqwz7ydZcCsA/7SvmrkF3AR2E/epZF2AOgj5YuJoy3330jxBQf8LDjZQfPtNr8VmsC8L+z9Ii/L7oSCtrclYDI1Bd8W"
                    + "yMwwi7vhbCLEH2AitTfjtjfoskps4SiaAqZTy8wp4BXrNd2qzM89C9wDFr3cc8Bt4FNwzlUFlwCPgDeAC1ujatYa9bWHrVHaoDXq"
                    + "haThsDWKoLnMSboqabqi5rKHBocJRiIp3Ji+H+EocJO8+WHAvLENIfXteT+Au8AT8t4I3xG1BhieTX12sU7eOnKNvAvoQJd//fwG"
                    + "3gOvgafAF5t7maDB8T/1cSSeN5uB8gAAAABJRU5ErkJggg=="},
            {"1",
                    "iVBORw0KGgoAAAANSUhEUgAAABQAAABCCAYAAABXXvbnAAABfklEQVR42u2YPUsdQRSGn917/YCUgnWaQJrUQohdQGz9C9oLaVLZ"
                    + "54/4I5IukA/SxICFYCFIukSLkEAU750nhWdgjFd3r9dGmAOHXYazz7zn7DTzos5HvlZPvIxkd+SaPfW5ijqHuqDuqD/VC6eLFN98"
                    + "VV+qA9R19XQKZTcp/agut8A2sAjI3SMBT4G1IfAMmAeaGYAtMABW8ssssCtK22g13RPQFngPjALsrMQWeAMcRdvjUDsp7Qv8BrwC"
                    + "DoBhrE3Krjk3QDMMyDtgC9gAlmItRZHxXAUedynMH84Bn4FPxV9PoWwMPAF2A+htaodRMCraTYW6nIuxEX0U5mFflLMolKRQSV/g"
                    + "tbNUbOJdjs29RgVWYAVWYAVWYAVWYAVWYAVWYAU+GGDT1zmZBtintu0L/Av86rjujoE/XUDjTv0d+FJczP+P7ADsdwFz4TnwFvgd"
                    + "rTthHIfAB8L3uy0bdaA+UrfV48KRS+pZYfc1fYAEMOem+iOAo7D5XmRD8h/JdaSX7gZRwQAAAABJRU5ErkJggg=="},
            {"4",
                    "iVBORw0KGgoAAAANSUhEUgAAACgAAABCCAYAAAAlkZRRAAADsUlEQVR42u2avasUVxiHn3NmVkVNEdCEqEUSEYtoIwkWFoEYtTOC"
                    + "EjAEKzGC/4C1YGFlZWFhEcQmhZDGRizSpEihhICCimj0GgnhqigY752Zn8V9DxzGuXfnuLuzF5kXXnZ2Z+ac53zM+7WDpFWSvKSD"
                    + "kn6XNKcFmZF0VFJmmkuia8Xg9ku6KemVpMoAS0kPJB2XtNIAs2kAfi3pL70tAXRW0gG7YSDJdQnogSPA54BMgzigAj4EDtlnBWR0"
                    + "KB7YZjBBqUEK2AR8BJR0LB5YB+RLXLMYfGeAq1p2rmkBTqXj9wrQLXdAesAesAfsAXvAHrAHnJrkE27fjRpP5hMceH11yndJGfwE"
                    + "Ziw3EFn73pKtys4lpQ/5BODmga2WLX5hoDeBn4Hb0QBcq2WX9EjSvJol5Ma/SdpquWpThcFZzpxbEeAPS/zjdq5J2hG14drmxeOY"
                    + "uQFQANuB08BXUV5d2XXfAGcshS3abi8/JlMlYA1w2Ja1is6FPirgW+CgLXPWZi+OC7AEPgZ2LpJHx9+/Az6x46yrp1jACtNhst5y"
                    + "cXU1g3FNp40xLlOuH6cd1Dtc57o21KkP5tBB5QmmJIs0loz0slxmfVe1vajotyRAmYcoo0aIzEdlts21HGxh7anmn4M3kl2TNIOf"
                    + "slCqczXAsGSrQ6MtHpLNwEaDJGpzxjSwFU7SI7NhS8HOAv8uMUPOOlvPQqFzsVVwwFPgsZmksnb+OXADOA/8CeRtAbuWq8AJ4K5P"
                    + "2INVC1VCW2q4NxzvAU4Ca1P24LjKdHFbTTXxALsP2LIc64NhAB7Y4IFXLM8qqwPWeOA+MDdKYjMBCenCrAcuAq8jpz+KpgYYagg2"
                    + "Kpu9e8AtD1wGrkTr7kbQ1H3mGh6aDHgJnAVmcvtyClgJ7AbWjvDguBb2dK4Wicf++X/gIXDOJg4nKaSFHwB7gS8je+QSwATst7yk"
                    + "KRgNv92yzgeRqwspwxPgV+BOMDlOUqAPe2GQOGuZuazPgAvAriGAN4DvgX/svioaZBlFRgWgPHLgPgqdUuO7ynxrm5Arj3Lj2HJU"
                    + "0X6cbwq3Upe1HqEUCa6uiMKzYqnIPB8hdB/1HtqYp74+2AP2gD3gewjougZMDbncNGbQm/+cG+IZZClGa5c6rvKbt4R8hsWL4yGa"
                    + "uQ7811ChmBhgiISeAb9YFaIejIZBPAYuAS9a+/AxvaXmJa0w/UnSfUlFVOUvJd2W9KNV+Af2qp9v83reuDSL/oo4JulhBPi3pB+i"
                    + "vysC3FDAN44b7geE3VPpAAAAAElFTkSuQmCC"},
            {"5",
                    "iVBORw0KGgoAAAANSUhEUgAAACgAAABCCAYAAAAlkZRRAAADr0lEQVR42u2aT2gdVRTGf/e+SRr/lNa2ghZ3ikIhLV3ZboTSUjdm"
                    + "IxU3uhDERcGNRbMqgboVbHctlGw0SEqhuFOQEgRBxZUUF6JUKgkapVhtjUnem8/FOze5Dkkz8zLzZoQ5MEnezeTeb84599zvOxkk"
                    + "jUhC0m5J70m6p76ldlVlYf4vJB0xDDskefsZSWADeyRNS/pDUk/Ds1TSsqSvJB2X5CQl9n0NYCJpSvXbt5IOGrDRANAD+4GXAAE9"
                    + "hm8CUmAcOAF0bNyHL08BewEXBodsYd3UsOyMHeWBx+ymus0DjwCjBnZtsGturtsE/AYsRWHGNwCc7FoFvgbuxriSASYqG5y3FPsE"
                    + "uJ5dKymYzFXl6ixwDliw8HbDL4oAnAN+BB6MJ9im97rAd8BHEbj/lLok50QO+ByYAsZskrQEkKnN39kIXFEPjtkkZeWis5ISgG4Y"
                    + "laTg08aXSgDYi+Zx2wWY3ckqIQfv93n9vGuytQBbgHVbMsADlflQ2moXFwWYRqfIdsqMs7VD7ettxuaLAOwBu4GHMxxSA6bVP8Cy"
                    + "0SzZqdLNHqFJzqfFdMtJYEdmEg3gPQErRlDngKvAz1EKDcRmnq5oHxwDXgVmgItGWJMAsm7CCvAQcAg4YKJpErgTQCYFFq2KsAbZ"
                    + "OQK8YaGfDNrEG4WqU9W5DI07DZy1XG9UoXbRLn/LRHzPAz81SHo6C/co8BqwywO3bLu7kmh8GSAFPAuMe+BX4IIlZyeq6lVcaU49"
                    + "44AHgOdDmfkQeNxiv68hOTkGHHWSfCSgXwRettqUVlRaUuAJ4OAW960Ct5wkInWvzCFeBXtaBp4DLllh1ibrdYFFn1FsVdfEEKkF"
                    + "YD7P/Unmj1crBOiiTZK3F+mSHHKwbO+pAJ9UHSdJIQe0oqkF2AJsAbYAW4D/T4C5eWbrwTIYblZNxe5XhY5xeQHGwEYiYll2L0YR"
                    + "Yw/ENTdhDW2vFfvciaSAK3ljdE2U7cwL0Eci5QVgwpS9v4+g2U5Yl4FngMOZlsdG6u92CHEPeB14l/4rAnWaIk/Ph0bhBHDeXN9j"
                    + "ON2urSrIKvB9AjwKvBOB6zSkwvwFfOwtH56MVFdT7BvgS29tCGjGqynxfw4+AO55+q23pryWEjbIDPAZ1gL+Afg7EtR1AEujjXMN"
                    + "OAP8GQZ+Aa6w3iuug9mE3J8F3gQWDUs3dLd2Ae8Dp+i/1dEZkueWLHq/A9PAZfOctzJDYmDuAG9bTXyFfnezalsCbgCf0m+g3jQ8"
                    + "a+AA/gX3i8PMBQF7XAAAAABJRU5ErkJggg=="},
            {"6",
                    "iVBORw0KGgoAAAANSUhEUgAAACgAAABCCAYAAAAlkZRRAAAExUlEQVR42u2aS4hcRRSGv1t9e8bETJLRqFkYSYKKmPgMBjSCEXwR"
                    + "oqgB8QGioi5UdOlSMFnqSnCn4kZE8DGLEBcq0REUUYyYQKITDT6CkkV8JWP6cX8XfSpT6XT3rb79mBH6QDHT3VVd//3Po8451Uii"
                    + "4CjZmJD0oqRjakhd0rSkDTavLMkV3acoOGdjQtJ2SUc0J5mkE5I+lXSlzU8lJcMC6GzDVNJjkioBsGbZLWmNgUuLMOnoXhxQAy4B"
                    + "ngHKQAYkwRzZezcAN9rrhIKbdSN+kzKwFVhnm7sW8/x7q4ExAzxwgCVjbx3wiIHrJAIW2wNp0AATG2VgG3BRE6vt1hwDKsNQcQpU"
                    + "gauBB3MY8Z/NAntsnSvCoutCtQ6YBJ4GLohgD+BV4ANbnw0SYGpqugK4I4K9BPgdmAL+DgAORMUloA4sBe4Hzoxkbwr4Mlg/ECdJ"
                    + "jD2Am4B7coB59n4G3gaO2h6F2IsBWLK/k8CjwLLIoPsO8BkwbvM1CICevTpwDXBtRMxLgB+Ad832sPUMAqAHtxJ4Koc9BWDeBL6y"
                    + "06PWC3udADoDKGALsDkykO8D3gL+tdc9sdcJoPe81ea5iyJsT8D7wEE7bWr0QVyb97znbTH7i7G9b0y9s/Zw9UEB9OxdaGFlcY7t"
                    + "JRbEXwP29pO9VgCdAZTFvQ2R3/MdsNv+z3qJe608tTnP88novcAZkZ77OnAgSChchFm0yjNPi5lpi6CcAvcBmyIcIwGmLTCryYZj"
                    + "JesUzNOmzSqm1tsjHeMf4BXgN2CJsZ92AaxqGvAh7TTHSiT5p04suD4HPBu5ya8WVtLIgKyAjD/M83daUlE3LVZP+a6gLETSVZL2"
                    + "dqjS+i0VSYckvSTpfKv+ymGJ6svIkqRxSTus8I6VzOYXHaFMS7q1uY5G0pi9uVHSwSGyp6AT4ffbJ2lzUHsnvk5YBjwJrOmlhu0h"
                    + "Fvtz+1Jghx2xNSB1ZpSXAXcPGVgroAKuA56wEwxnwfgBS+U1jyCT4O/DwHqg6uzUuM0+EPMrHsNy4E5g3AE3AxMRhZCCc7bXkVcV"
                    + "OuAWYDIFNlrtENtZGJacBSxNgYstSchjcAY4HGQ7RdRXN5tfC6zIscVxYFUKnB0kCu3O3BlrtU3bwmoBgP7BxoDngcdznNIBK2LP"
                    + "0F+A760RNFswIfV1zlHgSOQapZF2VQoSgqRA2y70UNdFxhM9UU15W1aQQbpd71jgMgL4fwUYG/A1bIBdd7pGKh4BHAEcARwBXOAS"
                    + "m24154BFapOkaUQDjMnNajYvpfi9W8Jce7nebxWfQ+O2qdYjg77FtiQWn7+sUU7WsR54AdjP3PVWEXuvWdF0feSDVlLgEHBuUHq2"
                    + "k002hiV14CcH7AKOB+lQO8kYXmcBGm3lww74kEZvOkZF/Rp5jXkBXwB/Ohq3k3sWSNhT4Ey7gOOORjP7ZRode5jfDpff+w3gY6Ds"
                    + "i+iPaFxlJfR4Ad2DZKb+r6018heQhA3rlZLea9M7HpRkQSNekmYkbbWmfiopab6GWCVpqstOf79kv6RtATgn6eRFTnjsLQcesnGe"
                    + "Rf1F9L83WKXxs4ETwOfAduDbwEky4BSAWJT3xroWuIvG72QuZ+7nKP2ytx/N9ncCn5jNjRngk37wH1EvlJTx2HmnAAAAAElFTkSu"
                    + "QmCC"},
    };
}
