# IdealCup

Minecraft Paper 서버에서 이미지 후보를 띄워 이상형 월드컵을 진행하는 플러그인입니다.
후보 이미지는 리소스팩으로 표시하고, 플레이어는 화면의 왼쪽/오른쪽 후보를 바라본 뒤 마우스 아이템을 우클릭해 투표합니다.

## 요구 사항

- Paper 1.21.x

## 명령어

관리 명령어는 OP 권한이 필요합니다.
`/idealcup`은 `/icup`으로도 사용할 수 있습니다.

| 명령어 | 설명                                                                              |
| --- |---------------------------------------------------------------------------------|
| `/idealcup start <후보수> <월드컵이름>` | 월드컵을 시작합니다. 후보 수는 2 이상의 2의 거듭제곱이어야 합니다. 예: `/idealcup start 64 애니 월드컵 64강`      |
| `/idealcup stop` | 진행 중인 월드컵을 중지합니다.                                                               |
| `/idealcup fetchsources [force] [병렬개수]` | URL이 있는 후보만 내려받아 `resourcepack-src/images/<id>.mp4`로 준비합니다. URL이 없는 후보 파일은 건드리지 않습니다. |
| `/idealcup buildpack [픽셀수] [fps] [병렬개수] [팩이름]` | `resourcepack-src`를 바탕으로 서버 최상위 폴더에 `resourcepack.zip`을 생성합니다.                  |
| `/idealcup status` | 현재 진행 상태를 확인합니다.                                                                |
| `/idealcup play <번호>` | 특정 후보를 보드 상에 표시합니다.                                                             |
| `/idealcup result` | 최종 결과 창을 엽니다. 이 명령은 일반 플레이어도 사용할 수 있습니다.                                        |
| `/idealcup rankingtest [개수]` | 랭킹 스크롤 화면을 테스트합니다.                                                              |
| `/idealcup packready <player>` | 지정한 플레이어를 관람 위치로 이동시킵니다.                                                        |
| `/idealcup set pos1` | 게임이 진행될 보드의 첫 번째 꼭짓점을 현재 위치로 지정합니다.                                             |
| `/idealcup set pos2` | 게임이 진행될 보드의 두 번째 꼭짓점을 현재 위치로 지정합니다.                                             |
| `/idealcup set debate-left` | 동점 상황일 시 변론이 이루어지는데 이 때 왼쪽 후보 측 대표가 이동할 위치를 저장합니다.                              |
| `/idealcup set debate-right` | 동점 상황일 시 변론이 이루어지는데 이 때 오른쪽 후보 측 대표가 이동할 위치를 저장합니다.                             |
| `/idealcup set lobby` | 서버 접속 시 이동할 대기 위치를 저장합니다.                                                       |
| `/idealcup set cinema` | 리소스팩 준비 완료 후 이동할 관람 위치를 저장합니다.                                                  |
| `/idealcup set debatetime <초>` | 동점 상황 발생 시 변론 시간을 설정합니다.                                                        |
| `/idealcup set votetime <초>` | 투표 시간을 설정합니다.                                                                   |

아이템 지급 명령어는 다음과 같습니다.

| 명령어 | 권한 | 설명                                                                      |
| --- | --- |-------------------------------------------------------------------------|
| `/마우스` | 없음 | 투표용 마우스 아이템을 지급합니다.                                                     |
| `/망원경` | 없음 | 관전용 망원경을 지급합니다.                                                         |
| `/리모컨` | `idealcup.admin` | 관리자 전용 리모컨을 지급합니다. 진행 중 우클릭하면 현재 단계를 즉시 넘깁니다. 또한 좌클릭으로 미디어를 재생할 수 있습니다. |

## 리소스팩 준비

IdealCup은 서버 최상위 폴더의 `resourcepack.zip`을 읽어 후보를 불러옵니다.
`resourcepack.zip`은 `/idealcup buildpack` 명령으로 생성합니다.

### 1. 원본 폴더 준비

서버의 플러그인 데이터 폴더에 다음 구조를 준비합니다.

```text
plugins/
  IdealCup/
    resourcepack-src/
      candidates.yml
      images/
        01.png
        02.jpg
        03.gif
        04.mp4
      sounds/
        ending_theme.ogg
        ending_theme2.mp3
```

### 2. 후보 목록 작성

`plugins/IdealCup/resourcepack-src/candidates.yml`에 후보 목록을 작성합니다.

```yaml
candidates:
  '01':
    name: '후보 이름 1'
  '02':
    name: '후보 이름 2'
```

- `name`: 게임 화면, 결과 창, 랭킹 화면에 표시될 후보 이름
- 후보 미디어 경로: 후보 ID 기준 `images/<id>.png`, `.jpg`, `.jpeg`, `.webp`, `.gif`, `.mp4`, `.mkv`, `.mov` 중 자동 탐색
- 영상 후보는 빌드 과정에서 프레임 이미지와 재생용 음성으로 변환됩니다.
- GIF와 애니메이션 PNG 후보는 화면에 표시되면 자동으로 재생됩니다.
- 최종 랭킹 BGM은 `ending_theme.ogg`, `ending_theme2.mp3`처럼 `ending_theme` 이름으로 시작하는 파일을 사용합니다.

### 3. URL 후보 준비

직접 파일을 넣는 대신 URL로 후보 영상을 준비할 수도 있습니다.
`/idealcup fetchsources`는 URL이 있는 후보만 내려받아 `resourcepack-src/images/<id>.mp4`로 저장합니다.
URL이 없는 후보의 기존 이미지나 영상 파일은 건드리지 않습니다.
`resourcepack-src/candidates.yml`에서 `url`, `start`, `duration`이 있는 후보만 준비합니다.

```yaml
candidates:
  '01':
    name: '후보 이름 1'
    url: 'https://example.com/video'
    start: 12.5
    duration: 8
```

- `start`, `duration`은 초 단위 숫자 또는 시간 형식 문자열을 사용할 수 있습니다.
- 예: `start: 80`, `start: 20.5`, `start: '00:01:20'`, `start: '00:00:20.500'`
- 시간 형식은 YAML에서 문자열로 읽히도록 따옴표로 감싸는 것을 권장합니다.

```text
/idealcup fetchsources
```

- `force`를 붙이면 기존 다운로드 파일을 덮어씁니다.
- 병렬 개수는 1부터 8까지 지정할 수 있습니다.
- 처음 실행 시 필요한 보조 도구가 없으면 플러그인이 다운로드를 시도합니다.

### 4. 리소스팩 생성

서버 안에서 OP 권한으로 실행합니다.

```text
/idealcup buildpack
```

기본값은 최대 픽셀 `256`, 애니메이션 `5fps`, 병렬 처리 `2개`입니다.
필요하면 다음처럼 조절할 수 있습니다.

```text
/idealcup buildpack 512 10 4 아이스크림 월드컵
```

- `픽셀수`: 16 이상 2048 이하
- `fps`: 1 이상 20 이하
- `병렬개수`: 1 이상 8 이하
- `팩이름`: 리소스팩 설명에 들어갈 이름

병렬개수는 되도록이면 기본값을 쓸 것을 권장합니다.
값을 높일 경우, 본인의 컴퓨터 사양에 따라 적절한 값을 설정하세요.
과도한 값을 설정할 경우, 팩을 빌드하다가 컴퓨터가 다운될 수도 있습니다.

성공하면 서버 최상위 폴더에 `resourcepack.zip`이 생성됩니다.
후보가 많거나 용량이 크면 `resourcepack-parts` 폴더에 분할 리소스팩도 함께 생성될 수 있습니다.

### 5. 클라이언트에 적용

생성된 `resourcepack.zip`을 플레이어가 적용해야 후보 이미지가 정상 표시됩니다.
서버 리소스팩으로 배포하려면 `server.properties`의 `resource-pack`에 배포 URL을 설정하세요.

`locations.cinema` 이동은 리소스팩 적용 완료 후 `/idealcup packready <player>`가 실행될 때 처리됩니다.
따라서 `server.properties`의 `resource-pack`만 사용하는 경우 리소스팩은 전송되지만, IdealCup이 적용 완료 시점을 직접 알 수 없어 `cinema` 이동은 자동으로 처리되지 않습니다.
`lobby -> 리소스팩 적용 -> cinema 이동` 흐름이 필요하면 PackRelay 같은 리소스팩 연동 플러그인에서 적용 완료 시 `/idealcup packready <player>`를 실행하도록 연결하는 방식을 권장합니다.

## 게임 진행 순서

1. 필요하면 `/idealcup set pos1`, `/idealcup set pos2`로 게임이 진행될 보드의 영역을 설정합니다.
2. 필요하면 `/idealcup set debate-left`, `/idealcup set debate-right`, `/idealcup set lobby`, `/idealcup set cinema`로 이동 위치를 잡습니다.
3. `resourcepack-src/candidates.yml`과 후보 미디어를 준비합니다.
4. `/idealcup buildpack`으로 `resourcepack.zip`을 생성합니다.
5. 플레이어가 리소스팩을 적용했는지 확인합니다.
6. `/idealcup start <후보수> <월드컵이름>`으로 시작합니다.
7. 투표 시간에는 후보를 바라보고 마우스 아이템을 우클릭합니다.
8. 동점이면 양쪽 투표자 중 대표가 뽑혀 변론을 진행하고, 변론 후 다시 투표합니다.
9. 마지막 후보가 남으면 최종 랭킹 화면과 결과 창을 표시합니다.

## 설정

최초 실행 시 `plugins/IdealCup/config.yml`과 `plugins/IdealCup/tools` 폴더가 생성됩니다.
`tools` 폴더에 `yt-dlp.exe`, `ffmpeg.exe`가 없으면 플러그인이 자동 다운로드를 시도합니다.
자동 다운로드에 실패하면 `tools` 폴더에 `yt-dlp.exe`, `ffmpeg.exe`를 직접 넣을 수 있습니다.
현재 기본값은 다음과 같습니다.

```yaml
timing:
  preview-seconds: 0
  vote-seconds: 10
  result-seconds: 3
  round-transition-seconds: 5
  debate-seconds: 15

locations:
  pos1:
    world: world
    x: -15.3
    y: -58.0
    z: -41.7
    yaw: 90.0
    pitch: 0.0
  pos2:
    world: world
    x: -15.3
    y: -43.0
    z: -5.3
    yaw: 90.0
    pitch: 0.0
  debate-left:
    world: world
    x: -17.5
    y: -54.0
    z: -33.5
    yaw: 90.0
    pitch: 0.0
  debate-right:
    world: world
    x: -17.5
    y: -53.0
    z: -13.5
    yaw: 90.0
    pitch: 0.0
  lobby:
    world: world
    x: -41.5
    y: -60.0
    z: -7.5
    yaw: -90.0
    pitch: 0,0
  cinema:
    world: world
    x: -43.7
    y: -60.0
    z: -0.3
    yaw: -90.0
    pitch: 0.0

ending-bgm:
  enabled: true
  gap-seconds: 10.0
  default-seconds: 120
```

### 설정 항목

- `timing.preview-seconds`: 후보가 표시된 뒤 투표로 넘어가기까지의 시간
- `timing.vote-seconds`: 투표 시간
- `timing.result-seconds`: 경기 결과 표시 시간
- `timing.round-transition-seconds`: 라운드 전환 대기 시간
- `timing.debate-seconds`: 동점 변론 시간
- `locations.pos1`, `locations.pos2`: 보드 표시 영역
- `locations.debate-left`, `locations.debate-right`: 동점 변론 대표 이동 위치
- `locations.lobby`: 플레이어 접속 시 이동 위치
- `locations.cinema`: `/idealcup packready <player>` 실행 시 이동할 관람 위치
- `ending-bgm.enabled`: 최종 랭킹 화면 BGM 사용 여부
- `ending-bgm.gap-seconds`: 최종 랭킹 BGM 사이 간격
- `ending-bgm.default-seconds`: BGM 길이를 알 수 없을 때 사용할 기본 길이

## 결과 기록

월드컵 종료 후 결과 기록은 `plugins/IdealCup/history.yml`에 저장됩니다.
최종 결과는 `/idealcup result`로 다시 열 수 있습니다.
