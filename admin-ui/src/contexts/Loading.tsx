import { Box, Spinner, Text, VStack } from "@chakra-ui/react";
import { motion } from "framer-motion";

const MotionBox = motion(Box);

const LoadingScreen = () => {
  return (
    <MotionBox
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      exit={{ opacity: 0 }}
      display="flex"
      alignItems="center"
      justifyContent="center"
      height="100vh"
      width="100vw"
      bg="gray.900"
      color="white"
      position="fixed"
      top="0"
      left="0"
      zIndex="9999"
    >
      <VStack spacing={4}>
        <Spinner size="xl" thickness="4px" speed="0.65s" color="blue.400" />
        <Text fontSize="lg" fontWeight="bold">
          Loading, please wait...
        </Text>
      </VStack>
    </MotionBox>
  );
};

export default LoadingScreen;
