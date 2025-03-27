# Scaleguard

Scaleguard is a high-performance, scalable networking solution that integrates multiple key capabilities, including load balancing, reverse proxying, secure exposure of services, and dynamic API management. It is designed to efficiently handle distributed workloads, provide high availability, and enable seamless traffic management across multiple clusters.

## Architecture

Scaleguard's architecture ensures high availability, security, and ease of management. Below is an overview of its core components:

![Scaleguard Architecture](scaleguard.svg)

## Landing Screen

Below is a screenshot of the Scaleguard landing screen:

![Scaleguard Landing Screen](screen1.png)

## Features
- **Load Balancer**: Distributes incoming traffic across multiple backend servers to ensure optimal resource utilization and reliability.
- **Reverse Proxy**: Acts as an intermediary to handle client requests, improving security and performance.
- **SafeExpose**: Securely expose internal services to external clients with controlled access.
- **Multi-Cluster Load Balancer**: Efficiently manage traffic across multiple clusters, ensuring high availability and failover support.
- **Async API Support**: Efficiently handle asynchronous communication patterns for real-time applications or services.
- **Queuing Mechanism**: Supports queuing for efficient workload distribution and message processing.
- **Built-in Observability**: Provides real-time monitoring, logging, and tracing for improved system visibility and debugging.
- **API Support**: Facilitates seamless integration with external services through a robust API framework.
- **Dynamic Configuration API**: Enables real-time updates to configurations without requiring restarts or downtime.
- **Dynamic DNS**: Automatically updates DNS records based on changes in infrastructure, improving service discovery.
- **Automatic Certificate Provisioning**: Automated provisioning and management of SSL/TLS certificates for secure communication.
- **Request Caching**: Stores frequently requested content at the load balancer level to improve response times and reduce load on backend servers.
- **High Scalability**: Efficiently handles a large number of concurrent connections and scales horizontally as demand increases.



## Getting Started

### Prerequisites
Ensure the following dependencies are installed before running Scaleguard:
- Java (JDK 11 or later)
- Node.js (for Admin UI)
- Maven (for building the backend)
- Git

### Installation & Setup

#### Cloning the Repository
```sh
git clone https://github.com/dhaneeshtb/scaleguard.git
cd scaleguard
```


### Running Scaleguard Server

#### Build and Start the Server Locally
```sh
mvn clean install
java -DadminUser=scaleguard -DadminPassword=Scaleguard123$ -jar target/scaleguard-1.0-SNAPSHOT.jar
```

### Running Admin UI

```sh
cd admin-ui
npm install
npm start
```
#### hosturl: http[s]://<ip/hostname of scaleguard> username and password provided above

### SafeExpose: Secure Application Exposure
Scaleguard provides a `SafeExpose` mechanism that allows users to expose their locally running applications to the public in a secure manner. This is achieved through the `safeexpose` script, available at [Scaleguard SafeExpose](https://github.com/dhaneeshtb/scalegurad-safeexpose.git).

For installation and usage details, please refer to the official [Scaleguard SafeExpose repository](https://github.com/dhaneeshtb/scalegurad-safeexpose.git).

### Disabling Default System DNS Server (If Required)
If you need to disable the system's default DNS service:
```sh
sudo systemctl disable systemd-resolved.service
sudo systemctl stop systemd-resolved
```

## Contributing
Contributions are welcome! Please follow these steps:
1. Fork the repository.
2. Create a feature branch (`git checkout -b feature-branch`).
3. Commit your changes (`git commit -m "Add new feature"`).
4. Push to the branch (`git push origin feature-branch`).
5. Create a pull request.

We appreciate your contributions to make Scaleguard even better!

## License
Scaleguard is released under the MIT License.

## Contact
For any inquiries, please contact: dhaneeshtnair@gmail.com  Contributions are welcome! Please follow these steps:
1. Fork the repository.
2. Create a feature branch (`git checkout -b feature-branch`).
3. Commit your changes (`git commit -m "Add new feature"`).
4. Push to the branch (`git push origin feature-branch`).
5. Create a pull request.

We appreciate your contributions to make Scaleguard even better!

