package controllers
package actions
package ipaddress

import models.{Asset, IpAddresses}
import models.shared.IpAddressConfig
import util.ApiTattler
import util.security.SecuritySpecification

import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json._
import java.sql.SQLException

// Allocate addresses for an asset
case class CreateAction(
  assetTag: String,
  spec: SecuritySpecification,
  handler: SecureController
) extends SecureAction(spec, handler) with AssetAction with AddressActionHelper {

  case class ActionDataHolder(asset: Asset, pool: String, count: Int) extends RequestDataHolder

  val dataForm = Form(tuple(
    "pool" -> optional(text),
    "count" -> optional(number(1, 10))
  ))

  override def validate(): Validation = withValidAsset(assetTag) { asset =>
    dataForm.bindFromRequest()(request).fold(
      err  => Left(RequestDataHolder.error400("Invalid pool or count specified")),
      form => {
        val (pool, count) = form
        val poolName = pool.map(convertPoolName(_))
        if (!IpAddresses.AddressConfig.isDefined)
          return Left(
            RequestDataHolder.error500("No address pools have been setup to allocate from")
          )
        val addressConfig = IpAddresses.AddressConfig.get
        poolName.filter(_.nonEmpty).foreach { p =>
          if (!addressConfig.hasPool(p)) {
            val ps = addressConfig.poolNames.mkString(", ")
            return Left(RequestDataHolder.error400("Pool %s does not exist. Valid pools are: %s".format(p,ps)))
          }
        }
        Right(ActionDataHolder(asset, poolName.getOrElse(""), count.getOrElse(1)))
      }
    )
  }

  override def execute(rd: RequestDataHolder) = rd match {
    case ActionDataHolder(asset, pool, count) => try {
      val poolName = pool.isEmpty match {
        case true =>
          // if default pool being used, store that pool name if the pool name isn't DEFAULT
          val addressConfig = IpAddresses.AddressConfig.get
          addressConfig.defaultPoolName.filter(_ != IpAddressConfig.DefaultPoolName)
                                        .getOrElse("")
        case false => pool
      }
      val created = IpAddresses.createForAsset(asset, count, Some(poolName))
      ApiTattler.notice(asset, userOption, "Created %d IP addresses".format(created.size))
      ResponseData(Status.Created, created.toJson)
    } catch {
      case e: SQLException =>
        handleError(RequestDataHolder.error409("Possible duplicate IP address"))
      case e: Throwable =>
        handleError(
          RequestDataHolder.error500("Unable to update address: %s".format(e.getMessage), e)
        )
    }
  }
}
