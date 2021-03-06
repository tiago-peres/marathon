package mesosphere.marathon
package state

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.core.pod.Network

import scala.collection.immutable.Seq

sealed trait Container extends Product with Serializable {

  import Container.PortMapping

  def portMappings: Seq[PortMapping]
  val volumes: Seq[VolumeWithMount[Volume]]

  val linuxInfo: Option[LinuxInfo]

  def hostPorts: Seq[Option[Int]] =
    portMappings.map(_.hostPort)

  def servicePorts: Seq[Int] =
    portMappings.map(_.servicePort)

  def copyWith(portMappings: Seq[PortMapping] = portMappings, volumes: Seq[VolumeWithMount[Volume]] = volumes, linuxInfo: Option[LinuxInfo] = linuxInfo): Container
}

object Container {

  case class Mesos(
      volumes: Seq[VolumeWithMount[Volume]] = Seq.empty,
      override val portMappings: Seq[PortMapping] = Nil,
      override val linuxInfo: Option[LinuxInfo] = None
  ) extends Container {

    override def copyWith(portMappings: Seq[PortMapping] = portMappings, volumes: Seq[VolumeWithMount[Volume]] = volumes, linuxInfo: Option[LinuxInfo] = linuxInfo) =
      copy(portMappings = portMappings, volumes = volumes)

  }

  case class Docker(
      volumes: Seq[VolumeWithMount[Volume]] = Seq.empty,
      image: String = "",
      override val portMappings: Seq[PortMapping] = Nil,
      privileged: Boolean = false,
      parameters: Seq[Parameter] = Nil,
      forcePullImage: Boolean = false,
      override val linuxInfo: Option[LinuxInfo] = None) extends Container {

    override def copyWith(portMappings: Seq[PortMapping] = portMappings, volumes: Seq[VolumeWithMount[Volume]] = volumes, linuxInfo: Option[LinuxInfo] = linuxInfo) =
      copy(portMappings = portMappings, volumes = volumes)
  }

  object Docker {
    implicit val validDockerContainer: Validator[Docker] = validator[Docker] { docker =>
      docker.image is notEmpty
      docker.linuxInfo is empty
    }
  }

  /**
    * @param containerPort The container port to expose
    * @param hostPort      The host port to bind
    * @param servicePort   The well-known port for this service
    * @param protocol      Layer 4 protocol to expose (i.e. "tcp", "udp" or "udp,tcp" for both).
    * @param name          Name of the service hosted on this port.
    * @param labels        This can be used to decorate the message with metadata to be
    *                      interpreted by external applications such as firewalls.
    * @param networkNames  Specifies one or more container networks, by name, for which this PortMapping applies.
    */
  case class PortMapping(
      containerPort: Int = AppDefinition.RandomPortValue,
      hostPort: Option[Int] = None, // defaults to HostPortDefault for BRIDGE mode, None for USER mode
      servicePort: Int = AppDefinition.RandomPortValue,
      protocol: String = PortMapping.TCP,
      name: Option[String] = None,
      labels: Map[String, String] = Map.empty[String, String],
      networkNames: Seq[String] = Nil
  )

  object PortMapping {
    val TCP = raml.NetworkProtocol.Tcp.value
    val UDP = raml.NetworkProtocol.Udp.value
    val UDP_TCP = raml.NetworkProtocol.UdpTcp.value
    val defaultInstance = PortMapping(name = Option("default"))

    val HostPortDefault = AppDefinition.RandomPortValue // HostPortDefault only applies when in BRIDGE mode
  }

  case class Credential(
      principal: String,
      secret: Option[String] = None)

  case class DockerPullConfig(secret: String)

  case class MesosDocker(
      volumes: Seq[VolumeWithMount[Volume]] = Seq.empty,
      image: String = "",
      override val portMappings: Seq[PortMapping] = Nil,
      credential: Option[Credential] = None,
      pullConfig: Option[DockerPullConfig] = None,
      forcePullImage: Boolean = false,
      override val linuxInfo: Option[LinuxInfo] = None) extends Container {

    override def copyWith(portMappings: Seq[PortMapping] = portMappings, volumes: Seq[VolumeWithMount[Volume]] = volumes, linuxInfo: Option[LinuxInfo] = linuxInfo) =
      copy(portMappings = portMappings, volumes = volumes)
  }

  object MesosDocker {
    val validMesosDockerContainer = validator[MesosDocker] { docker =>
      docker.image is notEmpty
    }
  }

  def validContainer(networks: Seq[Network], enabledFeatures: Set[String]): Validator[Container] = {
    import Network._
    val validGeneralContainer = validator[Container] { container =>
      container.volumes is every(VolumeWithMount.validVolumeWithMount(enabledFeatures))
      container.linuxInfo is optional(LinuxInfo.validLinuxInfoForContainerState)
    }

    new Validator[Container] {
      override def apply(container: Container): Result = container match {
        case _: Mesos => Success
        case dd: Docker => validate(dd)(Docker.validDockerContainer)
        case md: MesosDocker => validate(md)(MesosDocker.validMesosDockerContainer)
      }
    } and
      validGeneralContainer and
      implied(networks.hasBridgeNetworking)(validator[Container] { container =>
        container.portMappings is every(isTrue("hostPort is required for BRIDGE mode.")(_.hostPort.nonEmpty))
      })
  }
}

