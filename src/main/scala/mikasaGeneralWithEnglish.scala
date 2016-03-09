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
object MikasaGeneralWithEnglish {

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
    val takeRankNum = appProperties.getProperty("take_rank_num").toInt

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
        val tokens : java.util.List[Token] = CustomTwitterTokenizer3.tokenize(tweetText, dictFilePath)
        val pattern : Pattern = Pattern.compile("^[a-zA-Z]+$|^[0-9]+$") //「英数字か？」の正規表現
        for(index <- 0 to tokens.size()-1) { //各形態素に対して。。。
        val token = tokens.get(index)

          // 英単語文字を排除したい場合はこれを使う
          val matcher : Matcher = pattern.matcher(token.getSurfaceForm())

          if(token.getSurfaceForm().length() >= 2) {
            if (tokens.get(index).getAllFeaturesArray()(0) == "名詞" && (tokens.get(index).getAllFeaturesArray()(1) == "一般" || tokens.get(index).getAllFeaturesArray()(1) == "固有名詞")) {
              features += tokens.get(index).getSurfaceForm

              println(tokens.get(index).getAllFeaturesArray()(1))
            } else if (tokens.get(index).getPartOfSpeech == "カスタム名詞") {
              println(tokens.get(index).getPartOfSpeech)
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

    val topCounts60 = tweetStream.map((_, 1)
    ).reduceByKeyAndWindow(_+_, Seconds(60*60)
      ).map{case (topic, count) => (count, topic)
    }.transform(_.sortByKey(true))

    topCounts60.foreachRDD(rdd => {
      val sendMsg = new StringBuilder()

      val topList = rdd.take(takeRankNum)
      topList.foreach { case (count, tag) =>
        sendMsg.append("%s:%s,".format(tag, count))
      }
      val data = new KeyedMessage[String, String](TOPIC, sendMsg.toString())
      producer.send(data)
    })

    ssc.start()
    ssc.awaitTermination()

  }

}

object CustomTwitterTokenizer3 {

  def tokenize(text: String, dictPath: String): java.util.List[Token]  = {
    Tokenizer.builder().mode(Tokenizer.Mode.SEARCH)
      .userDictionary(dictPath)
      .build().tokenize(text)
  }
}
