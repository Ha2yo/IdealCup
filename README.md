# IdealCup

Minecraft Paper 서버에서 이미지 후보들을 띄워 이상형 월드컵을 진행하는 플러그인입니다. 
후보 이미지는 리소스팩으로 준비하고, 게임 중에는 플레이어가 화면의 왼쪽/오른쪽 후보를 바라본 뒤 마우스 아이템으로 투표합니다.

## 요구 사항

- Paper 1.21.x

## 명령어

관리 명령어는 `idealcup.admin` 권한이 필요합니다. 기본값은 OP입니다. `/idealcup`은 `/icup`으로도 사용할 수 있습니다.

| 명령어 | 설명 |
| --- | --- |
| `/idealcup start <월드컵이름> <참가자수>` | 이상형 월드컵을 시작합니다. 참가자 수는 2 이상의 2의 거듭제곱이어야 합니다. 예: `/idealcup start 음식 월드컵 32` |
| `/idealcup stop` | 진행 중인 월드컵을 중지합니다. |
| `/idealcup buildpack` | `plugins/IdealCup/resourcepack-src`를 바탕으로 서버 최상위 폴더에 `resourcepack.zip`을 생성합니다. |
| `/idealcup unpack` | 서버 최상위 `resourcepack.zip`에서 복원본을 추출합니다. `resourcepack-src`가 이미 있으면 원본 보호를 위해 `resourcepack-src-restored`에 풉니다. |
| `/idealcup unpack force` | 기존 `resourcepack-src`는 지우지 않고, 복원 대상 폴더만 덮어씁니다. 복원 이미지는 변환된 PNG입니다. |
| `/idealcup status` | 현재 진행 상태를 확인합니다. |
| `/idealcup forcewin <left\|right>` | 현재 경기의 왼쪽 또는 오른쪽 후보를 강제로 승리 처리합니다. |
| `/idealcup set pos1` | 후보 표시 영역의 첫 번째 꼭짓점을 현재 위치로 저장합니다. |
| `/idealcup set pos2` | 후보 표시 영역의 두 번째 꼭짓점을 현재 위치로 저장합니다. |
| `/idealcup set debate-left` | 동점 변론 때 왼쪽 후보 측 대표가 이동할 위치를 저장합니다. |
| `/idealcup set debate-right` | 동점 변론 때 오른쪽 후보 측 대표가 이동할 위치를 저장합니다. |
| `/idealcup set debatetime <초>` | 동점 변론 시간을 설정합니다. |
| `/idealcup set votetime <초>` | 투표 시간을 설정합니다. |

아이템 지급 명령어는 다음과 같습니다.

| 명령어 | 권한 | 설명 |
| --- | --- | --- |
| `/마우스` | 없음 | 투표용 마우스 아이템을 지급합니다. |
| `/망원경` | 없음 | 관전용 망원경을 지급합니다. |
| `/리모컨` | `idealcup.admin` | 관리자 전용 리모컨을 지급합니다. 진행 중 우클릭하면 현재 단계를 즉시 넘깁니다. |

## 리소스팩 준비

IdealCup은 서버 최상위 폴더의 `resourcepack.zip`을 읽어 후보를 불러옵니다. `resourcepack.zip`은 `/idealcup buildpack` 명령으로 생성합니다.

### 1. 원본 폴더 준비

서버의 플러그인 데이터 폴더에 다음 구조를 준비합니다.

```text
plugins/
  IdealCup/
    resourcepack-src/
      candidates.yml
      images/
        01.png
        02.png
        ...
```

### 2. 후보 이미지 넣기

후보 이미지는 PNG, JPG, JPEG, WebP 파일로 준비할 수 있습니다. 경로는 후보 ID 기준으로 고정됩니다. 예를 들어 후보 ID가 `01`이면 `images/01.png`, `images/01.jpg`, `images/01.jpeg`, `images/01.webp` 순서로 찾습니다.

파일명과 후보 ID는 원하는 값으로 둘 수 있지만, 영문 소문자, 숫자, `_`, `-` 조합을 추천합니다. 예시는 `01.png`부터 `64.png`까지 64강용 이미지가 들어가는 구조입니다.

### 3. `candidates.yml` 작성

`plugins/IdealCup/resourcepack-src/candidates.yml`에 후보 목록을 작성합니다. `image`는 쓰지 않아도 됩니다.

```yaml
candidates:
  '01':
    name: '후보 이름 1'
  '02':
    name: '후보 이름 2'
```

- `name`: 게임 화면과 결과에 표시될 후보 이름
- 원본 이미지 경로: 후보 ID 기준 `images/<id>.png`, `images/<id>.jpg`, `images/<id>.jpeg`, `images/<id>.webp` 중 자동 탐색
- 리소스팩 내부 이미지 경로: 항상 `images/<id>.png`로 변환
- 리소스팩 내부 이미지 크기: 긴 변 최대 512px, 비율 유지, 작은 이미지는 확대하지 않음

### 4. 리소스팩 생성

서버 안에서 OP 또는 `idealcup.admin` 권한으로 실행합니다.

```text
/idealcup buildpack
```

성공하면 서버 최상위 폴더에 `resourcepack.zip`이 생성됩니다. 원본이 JPG/JPEG/WebP여도 zip 안에는 긴 변 512px 이하의 PNG로 변환되어 들어갑니다. 플러그인은 이 zip 안의 `candidates.yml`, `images/<id>.png`, 생성된 모델 파일을 읽습니다.

`/idealcup buildpack`은 후보 이미지마다 다음 파일을 자동 생성합니다.

- `assets/idealcup/models/item/candidate_<id>.json`
- `assets/idealcup/items/candidate_<id>.json`
- `assets/idealcup/textures/item/...`

`pack.mcmeta`와 `assets/idealcup/...` 폴더는 마인크래프트 리소스팩 규격 때문에 zip 안에 생성됩니다. 직접 편집하는 원본 폴더는 `resourcepack-src/candidates.yml`과 `resourcepack-src/images`만 관리하면 됩니다.

`resourcepack-src`를 잃어버렸다면 `/idealcup unpack`으로 `resourcepack.zip`에서 다시 만들 수 있습니다. 단, 복원되는 이미지는 buildpack 때 변환된 512px 이하 PNG이며, 원래 넣었던 JPG/JPEG/WebP/영상 고해상도 파일로 돌아가지는 않습니다. 기존 `resourcepack-src`가 있을 때는 원본 열화를 막기 위해 `/idealcup unpack` 결과를 `resourcepack-src-restored`에 추출합니다.

### 5. 클라이언트에 적용

생성된 `resourcepack.zip`을 플레이어가 적용해야 후보 이미지가 정상 표시됩니다. 서버 리소스팩으로 강제하려면 `server.properties`의 `resource-pack`에 배포 URL을 넣고, 필요하면 `resource-pack-sha1`도 함께 설정하세요.

로컬 테스트만 할 때는 생성된 `resourcepack.zip`을 클라이언트의 리소스팩 폴더에 넣고 직접 적용해도 됩니다.

## 게임 진행 순서

1. `/idealcup set pos1`, `/idealcup set pos2`로 후보 표시 영역을 잡습니다.
2. 필요하면 `/idealcup set debate-left`, `/idealcup set debate-right`로 동점 변론 위치를 잡습니다.
3. `/idealcup buildpack`으로 `resourcepack.zip`을 생성합니다.
4. 플레이어가 리소스팩을 적용했는지 확인합니다.
5. `/idealcup start <월드컵이름> <참가자수>`로 시작합니다.
6. 투표 시간에는 후보를 바라보고 마우스 아이템을 우클릭합니다.
7. 동점이면 양쪽 투표자 중 대표가 뽑혀 변론을 진행하고, 다시 투표합니다.
8. 마지막 후보가 남으면 우승 기록이 `config.yml`의 `history`에 저장됩니다.

## 설정

`plugins/IdealCup/config.yml`에서 시간을 조정할 수 있습니다.

```yaml
timing:
  preview-seconds: 0
  vote-seconds: 20
  result-seconds: 3
  round-transition-seconds: 5
  debate-seconds: 20
```

주요 항목은 다음과 같습니다.

- `preview-seconds`: 후보가 표시된 뒤 투표로 넘어가기까지의 시간
- `vote-seconds`: 투표 시간
- `result-seconds`: 경기 결과 표시 시간
- `round-transition-seconds`: 첫 라운드 시작 전 대기 시간
- `debate-seconds`: 동점 변론 시간

위치 설정은 명령어로 저장하는 것을 권장합니다.
