package com.horizen.fixtures

import com.horizen.transaction.RegularTransaction
import com.horizen.secret.PrivateKey25519
import com.horizen.box.RegularBox
import java.util.{List => JList, ArrayList => JArrayList}

import com.horizen.proposition.PublicKey25519Proposition
import javafx.util.{Pair => JPair}
import scala.util.Random

trait TransactionFixture extends BoxFixture {

  def getRegularTransaction(inputsSecrets: Seq[PrivateKey25519], outputPropositions: Seq[PublicKey25519Proposition]): RegularTransaction = {
    val from: JList[JPair[RegularBox,PrivateKey25519]] = new JArrayList[JPair[RegularBox,PrivateKey25519]]()
    val to: JList[JPair[PublicKey25519Proposition, java.lang.Long]] = new JArrayList[JPair[PublicKey25519Proposition, java.lang.Long]]()
    var totalFrom: Long = outputPropositions.size

    for(secret <- inputsSecrets) {
      val value = 10 + Random.nextInt(10)
      from.add(new JPair(new RegularBox(secret.publicImage(), Random.nextInt(1000), value), secret))
      totalFrom += value
    }

    val minimumFee = 5L
    val maxTo = totalFrom - minimumFee
    var totalTo = 0L

    for(proposition <- outputPropositions) {
      val value = maxTo / outputPropositions.size
      to.add(new JPair(proposition, value))
      totalTo += value
    }

    val fee = totalFrom - totalTo

    RegularTransaction.create(from, to, fee, System.currentTimeMillis - Random.nextInt(10000))
  }

  def getTransaction () : RegularTransaction = {
    val from : JList[JPair[RegularBox,PrivateKey25519]] = new JArrayList[JPair[RegularBox,PrivateKey25519]]()
    val to : JList[JPair[PublicKey25519Proposition, java.lang.Long]] = new JArrayList[JPair[PublicKey25519Proposition, java.lang.Long]]()

    from.add(new JPair(new RegularBox(pk1.publicImage(), 1, 10), pk1))
    from.add(new JPair(new RegularBox(pk2.publicImage(), 1, 20), pk2))

    to.add(new JPair(pk7.publicImage(), 10L))

    RegularTransaction.create(from, to, 10L, 1547798549470L)
  }

  def getCompatibleTransaction () : RegularTransaction = {
    val from : JList[JPair[RegularBox,PrivateKey25519]] = new JArrayList[JPair[RegularBox,PrivateKey25519]]()
    val to : JList[JPair[PublicKey25519Proposition, java.lang.Long]] = new JArrayList[JPair[PublicKey25519Proposition, java.lang.Long]]()

    from.add(new JPair(new RegularBox(pk3.publicImage(), 1, 10), pk3))
    from.add(new JPair(new RegularBox(pk4.publicImage(), 1, 10), pk4))

    to.add(new JPair(pk7.publicImage(), 10L))

    RegularTransaction.create(from, to, 5L, 1547798549470L)
  }

  def getIncompatibleTransaction () : RegularTransaction = {
    val from : JList[JPair[RegularBox,PrivateKey25519]] = new JArrayList[JPair[RegularBox,PrivateKey25519]]()
    val to : JList[JPair[PublicKey25519Proposition, java.lang.Long]] = new JArrayList[JPair[PublicKey25519Proposition, java.lang.Long]]()

    from.add(new JPair(new RegularBox(pk1.publicImage(), 1, 10), pk1))
    from.add(new JPair(new RegularBox(pk6.publicImage(), 1, 10), pk6))

    to.add(new JPair(pk7.publicImage(), 10L))

    RegularTransaction.create(from, to, 5L, 1547798549470L)
  }


  def getTransactionList () : List[RegularTransaction] = {
    val txLst = List[RegularTransaction](getTransaction)
    txLst
  }

  def getCompatibleTransactionList () : List[RegularTransaction] = {
    val txLst = List[RegularTransaction](getCompatibleTransaction)
    txLst
  }

  def getIncompatibleTransactionList () : List[RegularTransaction] = {
    val txLst = List[RegularTransaction](getIncompatibleTransaction)
    txLst
  }
}
