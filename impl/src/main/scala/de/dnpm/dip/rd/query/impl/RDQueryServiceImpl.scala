package de.dnpm.dip.rd.query.impl 



import java.io.File
import scala.concurrent.Future
import scala.util.{
  Try,
  Failure
}
import cats.Monad
import de.dnpm.dip.util.{
  Completer,
  Logging
}
import de.dnpm.dip.model.{
  ClosedInterval,
  Site,
  Snapshot,
  Patient
}
import de.dnpm.dip.service.query.{
  BaseQueryService,
  Connector,
  Filters,
  Data,
  Query,
  QueryCache,
  BaseQueryCache,
  PatientFilter,
  InMemLocalDB,
  PreparedQueryDB,
  InMemPreparedQueryDB,
}
import de.dnpm.dip.coding.{
  Coding,
  CodeSystem
}
import de.dnpm.dip.coding.hgnc.HGNC
import de.dnpm.dip.rd.model.{
  HPO,
  Orphanet,
  RDPatientRecord
}
import de.dnpm.dip.rd.query.api._
import de.dnpm.dip.connector.peer2peer.PeerToPeerConnector



class RDQueryServiceProviderImpl
extends RDQueryServiceProvider
{

  override def getInstance: RDQueryService =
    return RDQueryServiceImpl.instance

}


object RDQueryServiceImpl extends Logging
{

  private val cache =
    new BaseQueryCache[RDQueryCriteria,RDFilters,RDResultSet,RDPatientRecord]


  private val connector =
    PeerToPeerConnector(
      "/api/rd/peer2peer/",
      PartialFunction.empty
    )


  private val db =
    new InMemLocalDB[Future,Monad,RDQueryCriteria,RDPatientRecord](
      RDQueryCriteriaOps.criteriaMatcher(strict = true)
    )
    with RDLocalDB


  private[impl] lazy val instance =
    new RDQueryServiceImpl(
      new InMemPreparedQueryDB[Future,Monad,RDQueryCriteria],
      db,
      connector,
      cache
    )

  Try(
    Option(System.getProperty("dnpm.dip.rd.query.data.generate")).get
  )
  .map(_.toInt)
  .foreach {
    n =>

      import de.ekut.tbi.generators.Gen
      import de.dnpm.dip.rd.gens.Generators._
      import scala.util.chaining._
      import scala.util.Random
      import scala.concurrent.ExecutionContext.Implicits.global

      implicit val rnd: Random =
        new Random

      for (i <- 0 until n){
        instance ! Data.Save(Gen.of[RDPatientRecord].next)
      }
  }
    
}


class RDQueryServiceImpl
(
  val preparedQueryDB: PreparedQueryDB[Future,Monad[Future],RDQueryCriteria,String],
  val db: RDLocalDB,
  val connector: Connector[Future,Monad[Future]],
  val cache: QueryCache[RDQueryCriteria,RDFilters,RDResultSet,RDPatientRecord]
)
extends BaseQueryService[
  Future,
  RDConfig
]
with RDQueryService
with Completers
{

  override val ResultSetFrom =
    new RDResultSetImpl(_,_)


  override def DefaultFilter(
    results: Seq[Snapshot[RDPatientRecord]]
  ): RDFilters = {

    val records =
      results.map(_.data)

    RDFilters(
      PatientFilter.on(records),
      HPOFilter(
        Option(
          records.flatMap(_.hpoTerms.map(_.value).toList)
            .toSet
        )
      ),
      DiagnosisFilter(
        Option(
          records.flatMap(_.diagnosis.categories.toList)
            .toSet
        )
      )
    )
  }


  override val localSite: Coding[Site] =
    connector.localSite
      
    
  override implicit val hpOntology: CodeSystem[HPO] =
    HPO.Ontology
      .getInstance[cats.Id]
      .get
      .latest

  override implicit val ordo: CodeSystem[Orphanet] =
    Orphanet.Ordo
      .getInstance[cats.Id]
      .get
      .latest


  override implicit val hgnc: CodeSystem[HGNC] =
    HGNC.GeneSet
      .getInstance[cats.Id]
      .get
      .latest

        
  import Completer.syntax._    

  //TODO: Complete codings, etc
  override val preprocess: RDPatientRecord => RDPatientRecord =
    _.complete


}
