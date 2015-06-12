import java.io.{InputStreamReader, FileInputStream}
import java.util.Properties
import java.util.regex.{Matcher, Pattern}

import kafka.producer.{KeyedMessage, Producer, ProducerConfig}
import org.apache.spark.streaming.twitter.TwitterUtils
import org.apache.spark.{SparkContext, SparkConf}
import org.apache.spark.streaming.{Seconds, StreamingContext}
import org.atilika.kuromoji.{Token, Tokenizer}

/**
 * Created by AKB428 on 2015/06/05.
 *
 * ref:https://github.com/AKB428/inazuma/blob/master/src/main/scala/inazumaTwitter.scala
 * ref:http://www.intellilink.co.jp/article/column/bigdata-kk01.html
 */
object MikasaGeneral {

  /**
   *
   * @param args args(0)=application.properties path (default config/application.properties)
   */
  def main(args: Array[String]): Unit = {


    val TOPIC = "ikazuchi0"

    val TOPIC_VIEW = "ikazuchi0.view"


    //--- Kafka Client Init Start
    val props = new Properties();

    props.put("metadata.broker.list","127.0.0.1:9092")
    props.put("serializer.class", "kafka.serializer.StringEncoder")
    props.put("request.required.acks", "1")

    val config = new ProducerConfig(props);

    val producer = new Producer[String,String](config)

    //--- Kafka Client Init End

    var configFileName = "config/application.properties"

    if (args.length == 1) {
      configFileName = args(0)
    }

    // Load Application Config
    val inStream = new FileInputStream(configFileName)
    val appProperties = new Properties()
    appProperties.load(new InputStreamReader(inStream, "UTF-8"))

    val dictFilePath = appProperties.getProperty("kuromoji.dict_path")

    // TODO @AKB428 サンプルコードがなぜかシステム設定になってるのでプロセス固有に設定できるように直せたら直す
    System.setProperty("twitter4j.oauth.consumerKey", appProperties.getProperty("twitter.consumer_key"))
    System.setProperty("twitter4j.oauth.consumerSecret", appProperties.getProperty("twitter.consumer_secret"))
    System.setProperty("twitter4j.oauth.accessToken", appProperties.getProperty("twitter.access_token"))
    System.setProperty("twitter4j.oauth.accessTokenSecret", appProperties.getProperty("twitter.access_token_secret"))


    // https://spark.apache.org/docs/latest/quick-start.html
    val conf = new SparkConf().setAppName("Mikasa Online Layer")
    conf.setMaster("local[*]")
    //val sc = new SparkContext(conf)

    // Spark Streaming本体（Spark Streaming Context）の定義
    val ssc = new StreamingContext(conf, Seconds(60)) // スライド幅60秒

    // 設定ファイルより検索ワードを設定
    val searchWordList = appProperties.getProperty("twitter.searchKeyword").split(",")

    // debug
    // println(searchWordList(0))

    val stream = TwitterUtils.createStream(ssc, None, searchWordList)

    // Twitterから取得したツイートを処理する
    val tweetStream = stream.flatMap(status => {

//      val tokenizer : Tokenizer = CustomTwitterTokenizer.builder().build()  // kuromojiの分析器
      val features : scala.collection.mutable.ArrayBuffer[String] = new collection.mutable.ArrayBuffer[String]() //解析結果を保持するための入れ物
      var tweetText : String = status.getText() //ツイート本文の取得

      val japanese_pattern : Pattern = Pattern.compile("[¥¥u3040-¥¥u309F]+") //「ひらがなが含まれているか？」の正規表現
      if(japanese_pattern.matcher(tweetText).find()) {  // ひらがなが含まれているツイートのみ処理
        // 不要な文字列の削除
        tweetText = tweetText.replaceAll("http(s*)://(.*)/", "").replaceAll("¥¥uff57", "") // 全角の「ｗ」は邪魔www

        // ツイート本文の解析
        val tokens : java.util.List[Token] = CustomTwitterTokenizer2.tokenize(tweetText, dictFilePath)
        val pattern : Pattern = Pattern.compile("^[a-zA-Z]+$|^[0-9]+$") //「英数字か？」の正規表現
        for(index <- 0 to tokens.size()-1) { //各形態素に対して。。。
        val token = tokens.get(index)

          // 英単語文字を排除したい場合はこれを使う
          val matcher : Matcher = pattern.matcher(token.getSurfaceForm())

          if(token.getSurfaceForm().length() >= 2 && !matcher.find()) {
            if (tokens.get(index).getAllFeaturesArray()(0) == "名詞" && (tokens.get(index).getAllFeaturesArray()(1) == "一般" || tokens.get(index).getAllFeaturesArray()(1) == "固有名詞")) {
              features += tokens.get(index).getSurfaceForm
            } else if (tokens.get(index).getPartOfSpeech == "カスタム名詞") {
              // println(tokens.get(index).getPartOfSpeech)
              // println(tokens.get(index).getSurfaceForm)
              features += tokens.get(index).getSurfaceForm
            }
          }
        }
      }
      (features)
    })

    // ソート方法を定義（必ずソートする前に定義）
    implicit val sortIntegersByString = new Ordering[Int] {
      override def compare(a: Int, b: Int) = a.compare(b)*(-1)
    }


    // ウインドウ集計（行末の括弧の位置はコメントを入れるためです、気にしないで下さい。）
    val topCounts60 = tweetStream.map((_, 1)                      // 出現回数をカウントするために各単語に「1」を付与
    ).reduceByKeyAndWindow(_+_, Seconds(60*60)   // ウインドウ幅(60*60sec)に含まれる単語を集める
      ).map{case (topic, count) => (count, topic)  // 単語の出現回数を集計
    }.transform(_.sortByKey(true))               // ソート


    // TODO スコアリングはタイトルで1つに集計しなおす必要がある
    // TODO 俺ガイル, oregaisu => 正式タイトルに直して集計


    // 出力
    topCounts60.foreachRDD(rdd => {
      // 出現回数上位20単語を取得

      // ソート
     // val rddSort = rdd.map(x => (x,1)).reduceByKey((x,y) => x + y).sortBy(_._2)

      val sendMsg = new StringBuilder()

      val topList = rdd.take(10)
      // コマンドラインに出力
      println("¥ nPopular topics in last 60*60 seconds (%s words):".format(rdd.count()))
      topList.foreach { case (count, tag) =>
        println("%s (%s tweets)".format(tag, count))
        sendMsg.append("%s:%s,".format(tag, count))
      }
      // Send Msg to Kafka
      // TOPスコア順にワードを送信
      val data = new KeyedMessage[String, String](TOPIC, sendMsg.toString())
      producer.send(data)
    })


    // ウインドウ集計（行末の括弧の位置はコメントを入れるためです、気にしないで下さい。）
    val topCountsShort = tweetStream.map((_, 1)
    ).reduceByKeyAndWindow(_+_, Seconds(5*60)
      ).map{case (topic, count) => (count, topic)
    }.transform(_.sortByKey(true))

    topCountsShort.foreachRDD(rdd => {
      val sendMsg = new StringBuilder()
      val topList = rdd.take(10)
      println("¥ nPopular topics in last short (%s words):".format(rdd.count()))
      topList.foreach { case (count, tag) =>
        println("%s (%s tweets)".format(tag, count))
        sendMsg.append("%s:%s,".format(tag, count))
      }
      // 別TOPICに送信
      val data = new KeyedMessage[String, String](TOPIC_VIEW, sendMsg.toString())
      producer.send(data)
    })

    ssc.start()
    ssc.awaitTermination()

  }

}

object CustomTwitterTokenizer2 {

  def tokenize(text: String, dictPath: String): java.util.List[Token]  = {
    Tokenizer.builder().mode(Tokenizer.Mode.SEARCH)
      .userDictionary(dictPath)
      .build().tokenize(text)
  }
}
