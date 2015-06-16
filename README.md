#Mikasa Recommend System Online Layer

#概要

Spark StreamingでTwitterから発言数を集計しKafkaに送信する

## Mikasaシステム概要図

http://tv-anime.xyz/g/mikasa_20150611.png

##事前に準備するもの

### Twitter 開発者アカウント

### Kafka

#### Kafka TOPIC 
* ikazuchi0
* ikazuchi0.vew

### 設定ファイル

設定ファイルのサンプルをコピーし値を記述

``cp config/application.properties.sample config/application.properties``

ユーザー辞書を使用しない場合は./dictionary/blank.txtを指定する

### Mikasa_RS

Kafkaの受信側のプログラムmikasa_rsを起動


## 起動

``sbt``

でSBTコンソールに入って

``run``


## Mikasa System フルインストールマニュアル
[Mikasa Install Manual](https://gist.github.com/AKB428/c30bc6a979e05fa3a022)
